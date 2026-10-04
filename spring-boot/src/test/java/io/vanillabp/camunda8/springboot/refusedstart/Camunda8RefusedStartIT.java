package io.vanillabp.camunda8.springboot.refusedstart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.camunda.client.CamundaClient;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.client.Camunda8Errors;
import io.vanillabp.camunda8.client.Camunda8RefusedStart;
import io.vanillabp.camunda8.processservice.Camunda8ProcessService;
import io.vanillabp.camunda8.springboot.SpringBootTestOnTheSharedCluster;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader.Entry;

/**
 * What a Camunda 8 cluster does with a start it will not carry out, measured against a
 * real cluster.
 * <p>
 * The phase-two outbox repeats a start until the entry is blocked, and a refusal which
 * reads the same on every attempt turns that repetition into a workflow lost slowly: the
 * application's transaction is committed, the aggregate is there, and the workflow never
 * comes into being. The adapter's answer is the classification in
 * {@code Camunda8Errors}, which names the codes a repetition cannot change. This class
 * asks the cluster which of its refusals carry such a code.
 * <p>
 * Three refusals and one non-refusal are pinned here. A request the cluster will not take
 * comes back as HTTP 400. A process the cluster does not hold answers 404 and a model
 * without a plain start event answers 409, and those two are the ones an application
 * really meets: a workflow module whose deployment went somewhere else, and a model
 * somebody changed to start on a timer while the code still calls startWorkflow. All
 * three block the outbox entry after a single attempt. A model which cannot evaluate an
 * expression is not refused at all: the instance exists and carries an incident, which
 * the cluster reports and no outbox entry waits for.
 * <p>
 * One thing stays unpinned here. An expression which merely reads a variable nobody
 * passed evaluates to null on Camunda 8, the instance runs through it and nothing fails
 * at all, which is why the third case demands a value such an expression cannot produce.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = RefusedStartTestApplication.class,
    properties = "spring.config.name=camunda8-refused-start-it")
public class Camunda8RefusedStartIT extends SpringBootTestOnTheSharedCluster {

  /**
   * Above the four megabytes a cluster takes per request by default. What the adapter
   * sends is the aggregate, so an aggregate of this size is a request no cluster of that
   * configuration ever accepts.
   */
  private static final int TOO_BIG_FOR_THE_CLUSTER = 5 * 1024 * 1024;

  @Autowired
  private RefusedStartWorkflowService workflowService;

  @Autowired
  private RefusedStartAggregateRepository repository;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private DataSource dataSource;

  /**
   * What this class asks about the phase-two outbox. It comes from the platform's test
   * tools, so neither the name of the table nor the value it writes on a blocked entry
   * is written down here.
   * <p>
   * This application runs the outbox table VanillaBP writes itself, so the reader is
   * told which table to read instead of looking for the one which is there.
   */
  private PhaseTwoOutboxReader outbox;

  @Autowired
  private Camunda8ClientFactoryRegistry clientFactoryRegistry;

  @Autowired
  private Camunda8ProcessService<RefusedStartAggregate> camunda8ProcessService;

  @BeforeEach
  void takeTheOutbox() {

    outbox = PhaseTwoOutboxReader.ofTheVanillaBpOutbox(dataSource);

  }

  private CamundaClient client() {

    return clientFactoryRegistry
        .getFactory("c8")
        .getClient();

  }

  @Test
  @DisplayName("A request the cluster will not take blocks the outbox entry after one attempt")
  public void aRequestTheClusterWillNotTakeBlocksTheEntryAfterOneAttempt() throws Exception {

    final var entryIdsBefore = entryIdsOfTheOutbox();

    // an application keeps a document in its aggregate, and every attribute of an
    // aggregate travels to the cluster as a process variable. This one is heavier than
    // the request a cluster accepts, which no repetition changes
    final var aggregate = transactionTemplate
        .execute(status -> workflowService.startWorkflow("x".repeat(TOO_BIG_FOR_THE_CLUSTER)));

    // the application is finished at this point: its transaction is committed and the
    // aggregate is there, while the workflow exists nowhere
    assertNotNull(aggregate.getId(), "the start returned a persisted aggregate");
    assertTrue(repository.findById(aggregate.getId()).isPresent(), "which is committed");

    assertTheStartIsBlockedAfterOneAttempt(entryIdsBefore);

  }

  @Test
  @DisplayName("A model without a none start event is refused for good")
  public void aModelWithoutANoneStartEventIsRefusedForGood() {

    // a model which is started by a timer and by nothing else, while the application
    // still calls startWorkflow. The cluster refuses that create for good
    deploy(
        "timer-start-only.bpmn",
        """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" id="Definitions_timer_only" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="TimerOnlyStartProcess" isExecutable="true">
                <bpmn:startEvent id="start">
                  <bpmn:outgoing>f1</bpmn:outgoing>
                  <bpmn:timerEventDefinition id="timer">
                    <bpmn:timeCycle xsi:type="bpmn:tFormalExpression">R1/PT30M</bpmn:timeCycle>
                  </bpmn:timerEventDefinition>
                </bpmn:startEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="end" />
                <bpmn:endEvent id="end">
                  <bpmn:incoming>f1</bpmn:incoming>
                </bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """);

    // the command phase two sends, sent without the outbox around it: what is asked here
    // is the cluster's answer and the adapter's verdict on it, and an outbox would only
    // repeat that answer for as long as the test is willing to wait
    final var refusal = assertThrows(
        Camunda8RefusedStart.class,
        () -> camunda8ProcessService
            .createProcessInstance("TimerOnlyStartProcess", Map.of("id", "1"), "1"));

    // what the cluster answers is a conflict, which is a repeatable answer everywhere
    // else: it is how a publication of a message which still lives comes back. For a
    // start it means the model, and a model is changed by a deployment rather than by a
    // repetition. This is measured behaviour, not a wish - a cluster which starts
    // answering such a request with 400 would break this assertion and would change
    // nothing about what the adapter does with it
    assertTrue(
        Camunda8Errors.rejection(refusal).startsWith("HTTP 409"),
        "the cluster refuses a create of a model without a none start event with a conflict, "
            + "but answered: "
            + Camunda8Errors.rejection(refusal));
    assertFalse(
        camunda8ProcessService.isPhaseTwoFailureRepeatable(refusal),
        "so the outbox blocks this start instead of repeating it for hours");

  }

  @Test
  @DisplayName("A model which cannot evaluate an expression becomes an incident, not a refused start")
  public void aModelWhichCannotEvaluateBecomesAnIncidentRatherThanARefusedStart() throws Exception {

    // the Camunda 7 case which started all of this is an expression the engine evaluates
    // WHILE it creates the instance: the create command ends, the outbox repeats it and
    // the workflow never exists. Camunda 8 evaluates nothing of the kind at creation
    // time, so the same broken expression has to be measured where a Camunda 8 model
    // evaluates one. A gateway condition is that place, and it stands in for the start
    // because the question is not which element fails but WHEN: after the instance
    // exists, or instead of it
    deploy(
        "cannot-evaluate.bpmn",
        """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" id="Definitions_cannot_evaluate" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="CannotEvaluateProcess" isExecutable="true">
                <bpmn:startEvent id="start">
                  <bpmn:outgoing>f1</bpmn:outgoing>
                </bpmn:startEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="gateway" />
                <bpmn:exclusiveGateway id="gateway">
                  <bpmn:incoming>f1</bpmn:incoming>
                  <bpmn:outgoing>f2</bpmn:outgoing>
                </bpmn:exclusiveGateway>
                <bpmn:sequenceFlow id="f2" sourceRef="gateway" targetRef="end">
                  <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">=order.total &gt; 100</bpmn:conditionExpression>
                </bpmn:sequenceFlow>
                <bpmn:endEvent id="end">
                  <bpmn:incoming>f2</bpmn:incoming>
                </bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """);

    final var instance = camunda8ProcessService
        .createProcessInstance("CannotEvaluateProcess", Map.of("id", "1"), "1");

    // the create came back with a key, so the outbox entry of this start is done and
    // nothing about the broken model reaches the outbox at all
    assertTrue(
        instance.getProcessInstanceKey() > 0,
        "the cluster takes the create of a model it cannot run");

    // the workflow EXISTS and carries an incident an operator can see and resolve. That
    // is the difference worth writing down: the Camunda 7 case loses the workflow
    // silently, this one keeps it and says what is wrong with it
    awaitUntil(
        () -> !client()
            .newIncidentSearchRequest()
            .filter(filter -> filter.processInstanceKey(instance.getProcessInstanceKey()))
            .send()
            .join()
            .items()
            .isEmpty(),
        "the incident of the instance whose gateway condition cannot be evaluated");

  }

  @Test
  @DisplayName("A start of a process the cluster does not hold blocks the outbox entry after one attempt")
  public void aStartOfAProcessTheClusterDoesNotHoldBlocksTheEntryAfterOneAttempt() throws Exception {

    // an application whose deployment went to another cluster looks exactly like this
    // one: the workflow service is wired, the outbox entry is written, and the cluster
    // the start reaches holds no such process. Taking the deployment away again is how
    // this test produces it, and the model is put back afterwards so that no other case
    // of this class depends on the order they run in
    final var deployed = theDeployedProcess();
    final var model = client()
        .newProcessDefinitionGetXmlRequest(deployed.key())
        .send()
        .join();
    try {
      client()
          .newDeleteResourceCommand(deployed.key())
          .send()
          .join();

      // the command the start sends, without the outbox around it: this is the cluster's
      // answer and the adapter's verdict on it
      final var refusal = assertThrows(
          Camunda8RefusedStart.class,
          () -> camunda8ProcessService
              .createProcessInstance(deployed.processId(), Map.of("id", "1"), "1"));
      assertTrue(
          Camunda8Errors.rejection(refusal).startsWith("HTTP 404"),
          "the cluster answers a start of a process it does not hold with a not-found, but answered: "
              + Camunda8Errors.rejection(refusal));
      assertFalse(
          camunda8ProcessService.isPhaseTwoFailureRepeatable(refusal),
          "and a deployment which is not here arrives through a deployment, never through a repetition");

      // and the same start the way an application makes it: the aggregate is committed,
      // the entry is written, and the dispatch meets the answer above
      final var entryIdsBefore = entryIdsOfTheOutbox();
      final var aggregate = transactionTemplate
          .execute(status -> workflowService.startWorkflow("a document any cluster would take"));
      assertNotNull(aggregate.getId(), "the start returned a persisted aggregate");

      assertTheStartIsBlockedAfterOneAttempt(entryIdsBefore);
    } finally {
      client()
          .newDeployResourceCommand()
          .addResourceStringUtf8(model, "refused-start.bpmn")
          .send()
          .join();
    }

  }

  /**
   * The process this application deployed, as the cluster knows it. Its id is scoped by
   * the name-clash-avoidance mode, so it is read from the cluster rather than written
   * into the test, and it is read by its plain name at the end.
   */
  private DeployedProcess theDeployedProcess() throws InterruptedException {

    // the search reads secondary storage, which is fed by the exporter: the deployment of
    // the boot is there a moment after the application is up
    awaitUntil(() -> !deployedProcesses().isEmpty(), "the deployment of the application under test");
    return deployedProcesses().getFirst();

  }

  private List<DeployedProcess> deployedProcesses() {

    // filtered by the cluster: the shared cluster holds the processes of every class of this
    // module, more than one page of an unfiltered search, and this one may be beyond it
    return client()
        .newProcessDefinitionSearchRequest()
        .filter(filter -> filter.processDefinitionId(id -> id.like("*RefusedStartProcess")))
        .send()
        .join()
        .items()
        .stream()
        .filter(definition -> definition.getProcessDefinitionId().endsWith("RefusedStartProcess"))
        .map(definition -> new DeployedProcess(
            definition.getProcessDefinitionKey(), definition.getProcessDefinitionId()))
        .toList();

  }

  /**
   * One deployed process definition: the key a resource is addressed by and the process
   * id a start names.
   */
  private record DeployedProcess(long key, String processId) {
  }

  /**
   * That the start which was made after the given entries is blocked, and blocked after
   * its FIRST attempt rather than after the last one the store would have allowed it.
   * <p>
   * The entry is found by what the outbox held before, because the class starts more
   * than one workflow and a blocked entry stays in the table.
   * <p>
   * The counter reads claims rather than finished dispatches. A poll raises it the moment
   * it takes the entry, and that claim leases the entry for
   * {@code vanillabp.outbox.attempt-frequency}. A dispatch which outlasts its lease is
   * claimed by the next poll and counted a second time, although the first one is still
   * on its way. The start of this class carries five megabytes on purpose: sending them
   * took 150 to 300 ms against {@code camunda/camunda:8.8.39} on 2026-09-22, and more
   * than half a second on the CI runner of that day, where this assertion read two
   * attempts and one dispatch. So {@code camunda8-refused-start-it.yaml} leases for
   * PT30S where the other integration tests lease for PT0.5S, and the lease is the whole
   * reason that one value differs. Measured on the same day: at PT0.1S the count is two
   * in three runs out of three, at PT0.5S it is one in five out of five.
   */
  private void assertTheStartIsBlockedAfterOneAttempt(
      final List<String> entryIdsBefore) throws InterruptedException {

    awaitUntil(
        () -> theEntryAddedTo(entryIdsBefore)
            .map(Entry::isBlocked)
            .orElse(Boolean.FALSE),
        "the outbox entry of the refused start to be blocked");

    // one attempt, not fifty: the adapter called the refusal permanent, so the store
    // wrote the block instead of counting 'vanillabp.outbox.block-after-attempts' down
    assertEquals(
        Integer.valueOf(1),
        theEntryAddedTo(entryIdsBefore).map(Entry::attempts).orElse(null),
        "the entry is blocked after the first attempt rather than after the last one");

  }

  /**
   * The whole outbox rather than the entries of one process: this application runs a
   * single process, so asking for that process would answer the same. The two cases
   * which start it tell their own entry apart by what the outbox held before.
   *
   * @return The id of every entry the outbox holds
   */
  private List<String> entryIdsOfTheOutbox() {

    return outbox
        .entries()
        .stream()
        .map(Entry::id)
        .toList();

  }

  private Optional<Entry> theEntryAddedTo(
      final List<String> entryIdsBefore) {

    return outbox
        .entries()
        .stream()
        .filter(entry -> !entryIdsBefore.contains(entry.id()))
        .findFirst();

  }

  private void deploy(
      final String name,
      final String model) {

    client()
        .newDeployResourceCommand()
        .addResourceStringUtf8(model, name)
        .send()
        .join();

  }

  private void awaitUntil(
      final Supplier<Boolean> condition,
      final String description) throws InterruptedException {

    // generous on purpose: the incident of the third case is read from secondary
    // storage, and the export pipeline is the slowest part of this class
    final var deadline = System.currentTimeMillis() + 120_000;
    while (!Boolean.TRUE.equals(condition.get())) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out waiting for: "
            + description);
      }
      Thread.sleep(200);
    }

  }

}
