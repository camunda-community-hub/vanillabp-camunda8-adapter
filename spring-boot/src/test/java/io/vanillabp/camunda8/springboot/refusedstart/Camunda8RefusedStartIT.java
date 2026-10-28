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
 * What a Camunda 8 cluster does with a start it will not carry out, measured against a real
 * cluster.
 * <p>
 * The phase-two outbox repeats a start until the entry is blocked. A refusal which reads the
 * same on every attempt turns that repetition into a workflow which is lost slowly. The
 * application's transaction is committed and the aggregate is there, but the workflow never
 * comes into being. The adapter answers this with the classification in
 * {@code Camunda8Errors}. It names the codes which a repetition cannot change. This class
 * asks the cluster which of its refusals carry such a code.
 * <p>
 * Three refusals and one case without a refusal are pinned here:
 * <ul>
 * <li>A request the cluster will not take comes back as HTTP 400.</li>
 * <li>A process the cluster does not hold answers 404. An application really meets this: a
 * workflow module whose deployment went somewhere else.</li>
 * <li>A model without a plain start event answers 409. An application meets this too:
 * somebody changed the model to start on a timer, while the code still calls
 * startWorkflow.</li>
 * </ul>
 * For the first two, the test shows that the outbox entry is blocked after a single attempt.
 * For the third, it shows that the adapter judges the answer as not repeatable, which is what
 * blocks an entry; no workflow service starts that model. A model which cannot evaluate an
 * expression is not refused at all. The instance exists and has an incident. The cluster
 * reports the incident, and no outbox entry waits for it.
 * <p>
 * One thing stays unpinned here. On Camunda 8, an expression which only reads a variable
 * nobody passed evaluates to null. The instance runs through it, and nothing fails at all.
 * That is why the third case demands a value which such an expression cannot produce.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = RefusedStartTestApplication.class,
    properties = "spring.config.name=camunda8-refused-start-it")
public class Camunda8RefusedStartIT extends SpringBootTestOnTheSharedCluster {

  /**
   * Above the four megabytes a cluster takes per request by default. The adapter sends the
   * aggregate. So no cluster with that configuration ever accepts an aggregate of this size.
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
   * What this class asks about the phase-two outbox. It comes from the platform's test tools.
   * So this class does not spell out the name of the table, or the value written on a blocked
   * entry.
   * <p>
   * This application runs the outbox table which VanillaBP writes itself. So the reader is told
   * which table to read. It does not look for the one which is there.
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

    // an application keeps a document in its aggregate. Every attribute of an aggregate
    // travels to the cluster as a process variable. This one is bigger than a request the
    // cluster accepts, and no repetition changes that
    final var aggregate = transactionTemplate
        .execute(status -> workflowService.startWorkflow("x".repeat(TOO_BIG_FOR_THE_CLUSTER)));

    // the application is finished at this point. Its transaction is committed and the
    // aggregate is there, but the workflow exists nowhere
    assertNotNull(aggregate.getId(), "the start returned a persisted aggregate");
    assertTrue(repository.findById(aggregate.getId()).isPresent(), "which is committed");

    assertTheStartIsBlockedAfterOneAttempt(entryIdsBefore);

  }

  @Test
  @DisplayName("A model without a none start event is refused for good")
  public void aModelWithoutANoneStartEventIsRefusedForGood() {

    // a model which is started by a timer and by nothing else, while the application
    // still calls startWorkflow. The cluster refuses that create for good.
    //
    // The timer waits for a date far in the future, so it never fires while tests run.
    // This class shares its cluster with the other test classes of this module. A timer
    // which fires starts an instance in the middle of whichever class runs then. That
    // class checks for workflows left behind and reports the instance as its own. A
    // timer of 30 minutes did exactly that on a nightly run
    deploy(
        "timer-start-only.bpmn",
        """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" id="Definitions_timer_only" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="TimerOnlyStartProcess" isExecutable="true">
                <bpmn:startEvent id="start">
                  <bpmn:outgoing>f1</bpmn:outgoing>
                  <bpmn:timerEventDefinition id="timer">
                    <bpmn:timeDate xsi:type="bpmn:tFormalExpression">2999-12-31T23:59:59Z</bpmn:timeDate>
                  </bpmn:timerEventDefinition>
                </bpmn:startEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="end" />
                <bpmn:endEvent id="end">
                  <bpmn:incoming>f1</bpmn:incoming>
                </bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """);

    // the command phase two sends, sent without the outbox around it. This asks for the
    // cluster's answer and the adapter's verdict on it. An outbox would only repeat that
    // answer for as long as the test is willing to wait
    final var refusal = assertThrows(
        Camunda8RefusedStart.class,
        () -> camunda8ProcessService
            .createProcessInstance("TimerOnlyStartProcess", Map.of("id", "1"), "1"));

    // the cluster answers with a conflict. Everywhere else a conflict can be repeated. It
    // is how the publication of a message comes back while the message still lives. For a
    // start the conflict is about the model, and only a deployment changes a model, not a
    // repetition. This is measured behaviour, not a wish. If a cluster starts to answer
    // such a request with 400, this assertion breaks. What the adapter does with the
    // answer does not change
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

    // the Camunda 7 case which started all of this is an expression which the engine
    // evaluates WHILE it creates the instance. The create command ends, the outbox repeats
    // it, and the workflow never exists. Camunda 8 evaluates nothing of the kind at creation
    // time. So the same broken expression has to be measured where a Camunda 8 model
    // evaluates one. A gateway condition is such a place. It stands in for the start,
    // because the question is not which element fails, but WHEN: after the instance exists,
    // or instead of it
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

    // the create came back with a key. So the outbox entry of this start is done, and
    // nothing about the broken model reaches the outbox at all
    assertTrue(
        instance.getProcessInstanceKey() > 0,
        "the cluster takes the create of a model it cannot run");

    // the workflow EXISTS and has an incident which an operator can see and resolve. That
    // is the difference worth writing down. The Camunda 7 case loses the workflow without a
    // word. This one keeps it and says what is wrong with it
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

    // an application whose deployment went to another cluster looks exactly like this one.
    // The workflow service is wired and the outbox entry is written. But the cluster which
    // the start reaches holds no such process. This test produces that by taking the
    // deployment away again. The model is put back afterwards. So no other case of this
    // class depends on the order in which they run
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

      // the command the start sends, without the outbox around it. This is the cluster's
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

      // and the same start the way an application makes it. The aggregate is committed,
      // the entry is written, and the dispatch gets the answer above
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
   * The process this application deployed, as the cluster knows it. The name-clash-avoidance
   * mode scopes its id. So the test reads the id from the cluster and does not spell it out.
   * It finds the id by the plain name at its end.
   */
  private DeployedProcess theDeployedProcess() throws InterruptedException {

    // the search reads secondary storage, and the exporter fills it. So the deployment of
    // the boot shows up a moment after the application is up
    awaitUntil(() -> !deployedProcesses().isEmpty(), "the deployment of the application under test");
    return deployedProcesses().getFirst();

  }

  private List<DeployedProcess> deployedProcesses() {

    // the cluster filters. The shared cluster holds the processes of every class of this
    // module. That is more than one page of an unfiltered search, and this one may not be
    // on that page
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
   * One deployed process definition: the key which addresses the resource, and the process
   * id which a start names.
   */
  private record DeployedProcess(long key, String processId) {
  }

  /**
   * Asserts that the start made after the given entries is blocked. And that it is blocked
   * after its FIRST attempt, not after the last one the store would have allowed.
   * <p>
   * The entry is found by what the outbox held before. The class starts more than one
   * workflow, and a blocked entry stays in the table.
   * <p>
   * The counter counts claims, not finished dispatches. A poll raises it the moment it takes
   * the entry. That claim leases the entry for {@code vanillabp.outbox.attempt-frequency}. If
   * a dispatch outlasts its lease, the next poll claims it and counts it a second time,
   * although the first dispatch is still on its way. The start of this class carries five
   * megabytes on purpose. On 2026-09-22, sending them took 150 to 300 ms against
   * {@code camunda/camunda:8.8.39}. On the CI runner of that day it took more than half a
   * second, and this assertion read two attempts and one dispatch. So
   * {@code camunda8-refused-start-it.yaml} leases for PT30S, while the other integration tests
   * lease for PT0.5S. The lease is the only reason why that one value differs. Measured on the
   * same day: at PT0.1S the count is two in three runs out of three, at PT0.5S it is one in
   * five out of five.
   */
  private void assertTheStartIsBlockedAfterOneAttempt(
      final List<String> entryIdsBefore) throws InterruptedException {

    awaitUntil(
        () -> theEntryAddedTo(entryIdsBefore)
            .map(Entry::isBlocked)
            .orElse(Boolean.FALSE),
        "the outbox entry of the refused start to be blocked");

    // one attempt, not fifty. The adapter called the refusal permanent. So the store wrote
    // the block, and did not count 'vanillabp.outbox.block-after-attempts' down
    assertEquals(
        Integer.valueOf(1),
        theEntryAddedTo(entryIdsBefore).map(Entry::attempts).orElse(null),
        "the entry is blocked after the first attempt rather than after the last one");

  }

  /**
   * The whole outbox, not the entries of one process. This application runs a single
   * process, so asking for that process would give the same answer. The two cases which
   * start it find their own entry by what the outbox held before.
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

    // generous on purpose. The incident of the third case is read from secondary storage,
    // and the export pipeline is the slowest part of this class
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
