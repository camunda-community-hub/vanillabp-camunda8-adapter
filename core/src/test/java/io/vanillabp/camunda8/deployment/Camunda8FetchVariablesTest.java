package io.vanillabp.camunda8.deployment;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

import io.camunda.client.api.worker.JobWorkerBuilderStep1;
import io.vanillabp.camunda8.Camunda8ProcessingContext;
import io.vanillabp.camunda8.TestCollaborators;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.wiring.Camunda8FetchVariables;
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a worker of this adapter asks the cluster for. The derivation has to be
 * COMPLETE: a variable missing from the list is a variable the handler simply does not
 * see any more, and nothing fails to say so. The cases below are the ones the adapter
 * reads a variable in.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8FetchVariablesTest {

  private static final String MODULE = "test-module";

  /**
   * Two processes of one workflow module, whose tasks share the task definition
   * <code>approve</code> - so ONE worker serves both.
   */
  private static final String TWO_PROCESSES = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="Loans" isExecutable="true">
          <bpmn:serviceTask id="ApproveLoan">
            <bpmn:extensionElements>
              <zeebe:taskDefinition type="approve" />
            </bpmn:extensionElements>
          </bpmn:serviceTask>
        </bpmn:process>
        <bpmn:process id="Cards" isExecutable="true">
          <bpmn:serviceTask id="ApproveCard">
            <bpmn:extensionElements>
              <zeebe:taskDefinition type="approve" />
            </bpmn:extensionElements>
          </bpmn:serviceTask>
        </bpmn:process>
      </bpmn:definitions>
      """;

  /**
   * A task nested in a multi-instance subprocess, itself multi-instance - the case which
   * makes the fetch list depend on the ELEMENT rather than on the process.
   */
  private static final String NESTED_MULTI_INSTANCE = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="MiProcess" isExecutable="true">
          <bpmn:subProcess id="Outer">
            <bpmn:multiInstanceLoopCharacteristics>
              <bpmn:extensionElements>
                <zeebe:loopCharacteristics inputCollection="=groups" inputElement="group" />
              </bpmn:extensionElements>
            </bpmn:multiInstanceLoopCharacteristics>
            <bpmn:serviceTask id="Inner">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="inner" />
              </bpmn:extensionElements>
              <bpmn:multiInstanceLoopCharacteristics>
                <bpmn:extensionElements>
                  <zeebe:loopCharacteristics inputCollection="=items" inputElement="item" />
                </bpmn:extensionElements>
              </bpmn:multiInstanceLoopCharacteristics>
            </bpmn:serviceTask>
          </bpmn:subProcess>
        </bpmn:process>
      </bpmn:definitions>
      """;

  /**
   * What a model hands to its own tasks and computes for itself - the four constructs
   * which declare a variable in Camunda 8.
   */
  private static final String DECLARING_MODEL = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="Declaring" isExecutable="true">
          <bpmn:serviceTask id="Rate">
            <bpmn:extensionElements>
              <zeebe:taskDefinition type="rate" />
              <zeebe:ioMapping>
                <zeebe:input source="=&quot;acme-rating&quot;" target="ratingProvider" />
                <zeebe:output source="=result" target="rating" />
              </zeebe:ioMapping>
            </bpmn:extensionElements>
          </bpmn:serviceTask>
          <bpmn:scriptTask id="Compute">
            <bpmn:extensionElements>
              <zeebe:script expression="=1 + 1" resultVariable="computed" />
            </bpmn:extensionElements>
          </bpmn:scriptTask>
          <bpmn:businessRuleTask id="Decide">
            <bpmn:extensionElements>
              <zeebe:calledDecision decisionId="risk" resultVariable="risk" />
            </bpmn:extensionElements>
          </bpmn:businessRuleTask>
        </bpmn:process>
      </bpmn:definitions>
      """;

  /**
   * A deployment service whose core answers the given aggregate-ID variable per BPMN
   * process and no <code>&#64;TaskParam</code> anywhere.
   */
  private Camunda8DeploymentService deploymentService(
      final Function<String, String> aggregateIdNames) {

    return deploymentService(aggregateIdNames, taskDefinition -> List.of());

  }

  /**
   * A deployment service whose core answers the given aggregate-ID variable per BPMN
   * process and the given <code>&#64;TaskParam</code> names per task definition - the two
   * questions the derivation asks it.
   */
  private Camunda8DeploymentService deploymentService(
      final Function<String, String> aggregateIdNames,
      final Function<String, List<String>> taskParameters) {

    // decomposition is what a call activity of these models is for, so both processes are
    // served by one workflow service as long as the core knows them at all
    return deploymentService(
        aggregateIdNames,
        taskParameters,
        (
            bpmnProcessId,
            otherBpmnProcessId) -> (aggregateIdNames.apply(bpmnProcessId) != null) && (aggregateIdNames
                .apply(otherBpmnProcessId) != null));

  }

  /**
   * The same, with the core's answer about the workflow aggregate of a called process
   * spelled out - the one which decides whether a call activity is decomposition or the
   * start of a business case of its own.
   */
  private Camunda8DeploymentService deploymentService(
      final Function<String, String> aggregateIdNames,
      final Function<String, List<String>> taskParameters,
      final BiPredicate<String, String> shareTheWorkflowAggregate) {

    final var invoker = new Camunda8DeploymentServiceTest.NoOpInvoker() {

      @Override
      public String resolveWorkflowAggregateIdName(
          final String workflowModuleId,
          final String bpmnProcessId) {

        final var name = aggregateIdNames.apply(bpmnProcessId);
        if (name == null) {
          throw new IllegalStateException("no workflow service serves '%s'".formatted(bpmnProcessId));
        }
        return name;

      }

      @Override
      public boolean workflowsShareTheWorkflowAggregate(
          final String workflowModuleId,
          final String bpmnProcessId,
          final String otherBpmnProcessId) {

        return shareTheWorkflowAggregate.test(bpmnProcessId, otherBpmnProcessId);

      }

      @Override
      public Collection<String> taskParameterNames(
          final String workflowModuleId,
          final String bpmnProcessId,
          final String taskDefinitionOrActivityId) {

        return taskParameters.apply(taskDefinitionOrActivityId);

      }

    };
    final var deploymentService = DeploymentServiceUnderTest.of(
        "c8", new Camunda8ClientFactory("c8", new Camunda8AdapterConfiguration()), TestCollaborators
            .of(invoker),
        (
            m,
            p,
            t) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
        Duration.ofHours(1));
    return deploymentService;

  }

  /**
   * Runs the deployment pipeline up to <code>wireBpmn</code>, which is what fills the
   * multi-instance registry the derivation reads.
   */
  private Camunda8ProcessingContext wire(
      final Camunda8DeploymentService deploymentService,
      final String xml) {

    final var models = deploymentService
        .readBpmn(MODULE, "test.bpmn", new ByteArrayInputStream(xml.getBytes(UTF_8)), true);
    Camunda8ProcessingContext context = null;
    for (final var model : models) {
      context = deploymentService.prepareBpmn(MODULE, context, "test.bpmn", model.getKey(), model.getValue());
    }
    for (final var model : models) {
      deploymentService.wireBpmn(MODULE, "test.bpmn", model.getKey(), model.getValue(), context);
    }
    return context;

  }

  @Test
  @DisplayName("one worker serving two processes fetches BOTH aggregate-ID variables")
  public void theUnionCoversEveryProcessTheWorkerServes() {

    final var deploymentService = deploymentService(
        bpmnProcessId -> "Loans".equals(bpmnProcessId)
            ? "loanId"
            : "cardId");
    wire(deploymentService, TWO_PROCESSES);

    final var selection = deploymentService.fetchVariablesOf(
        MODULE,
        List
            .of(
                new Camunda8DeploymentService.ServedElement("Loans", "ApproveLoan", "approve"),
                new Camunda8DeploymentService.ServedElement("Cards", "ApproveCard", "approve")));

    assertFalse(selection.all(), "the derivation covers both processes, so nothing has to be fetched blindly");
    assertEquals(
        List.of("cardId", "loanId", "vanillabpMiParents"),
        selection.names(),
        "fetchVariables is a list, so two processes disagreeing about the name is no conflict - and the "
            + "order is sorted, because the gateway compares the list of two job streams");

  }

  @Test
  @DisplayName("the list is sorted, so it is the same after a restart")
  public void theListIsStableAcrossRestarts() {

    final var deploymentService = deploymentService(
        bpmnProcessId -> "Loans".equals(bpmnProcessId)
            ? "loanId"
            : "cardId");
    wire(deploymentService, TWO_PROCESSES);

    final var served = List
        .of(
            new Camunda8DeploymentService.ServedElement("Cards", "ApproveCard", "approve"),
            new Camunda8DeploymentService.ServedElement("Loans", "ApproveLoan", "approve"));

    assertEquals(
        List.of("cardId", "loanId", "vanillabpMiParents"),
        deploymentService.fetchVariablesOf(MODULE, served).names(),
        "the other order of the same processes produces the same list - job streams stay equivalent");

  }

  @Test
  @DisplayName("a task in nested iterations fetches the multi-instance variables of all of them")
  public void theMultiInstanceContextIsPartOfTheList() {

    final var deploymentService = deploymentService(bpmnProcessId -> "id");
    wire(deploymentService, NESTED_MULTI_INSTANCE);

    final var selection = deploymentService.fetchVariablesOf(
        MODULE,
        List.of(new Camunda8DeploymentService.ServedElement("MiProcess", "Inner", "inner")));

    assertEquals(
        List
            .of(
                "id",
                "vanillabpMiElement_Inner",
                "vanillabpMiElement_Outer",
                "vanillabpMiIndex_Inner",
                "vanillabpMiIndex_Outer",
                "vanillabpMiParents",
                "vanillabpMiTotal_Inner",
                "vanillabpMiTotal_Outer"),
        selection.names(),
        "index, total and element of every iteration enclosing the task - without them the core "
            + "cannot report the iteration the job belongs to");

  }

  @Test
  @DisplayName("an element without iterations contributes the aggregate-ID variable alone")
  public void aPlainTaskFetchesOneVariable() {

    final var deploymentService = deploymentService(bpmnProcessId -> "id");
    wire(deploymentService, TWO_PROCESSES);

    assertEquals(
        List.of("id", "vanillabpMiParents"),
        deploymentService
            .fetchVariablesOf(
                MODULE,
                List.of(new Camunda8DeploymentService.ServedElement("Loans", "ApproveLoan", "approve")))
            .names(),
        "plus the chain a caller naming its process by an expression hands down, which every "
            + "worker serving an element asks for because no deployment knows who can reach it");

  }

  @Test
  @DisplayName("a BPMN process no workflow service serves is fetched blindly rather than incompletely")
  public void anUnknownAggregateFallsBackToEverything() {

    final var deploymentService = deploymentService(bpmnProcessId -> null);
    wire(deploymentService, TWO_PROCESSES);

    assertTrue(
        deploymentService
            .fetchVariablesOf(
                MODULE,
                List.of(new Camunda8DeploymentService.ServedElement("Loans", "ApproveLoan", "approve")))
            .all(),
        "a list missing exactly the name the handler needs would be worse than the old behaviour");

  }

  @Test
  @DisplayName("the @TaskParam names of the served task are fetched, whatever the model declares")
  public void theDeclaredTaskParametersAreFetched() {

    final var deploymentService = deploymentService(
        bpmnProcessId -> "id",
        taskDefinition -> "rate".equals(taskDefinition)
            ? List.of("ratingProvider")
            : List.of());
    wire(deploymentService, DECLARING_MODEL);

    final var selection = deploymentService.fetchVariablesOf(
        MODULE,
        List.of(new Camunda8DeploymentService.ServedElement("Declaring", "Rate", "rate")));

    assertEquals(
        List.of("id", "ratingProvider", "vanillabpMiParents"),
        selection.names(),
        "the handler reads one of the values this model computes, and the other three are the "
            + "model's own business - reading them off the model would fetch all four");

  }

  @Test
  @DisplayName("a worker serving two tasks fetches the union of their parameters")
  public void theUnionCoversTheParametersOfEveryTaskTheWorkerServes() {

    final var deploymentService = deploymentService(
        bpmnProcessId -> "id",
        taskDefinition -> "approve".equals(taskDefinition)
            ? List.of("region", "amount")
            : List.of());
    wire(deploymentService, TWO_PROCESSES);

    assertEquals(
        List.of("amount", "id", "region", "vanillabpMiParents"),
        deploymentService
            .fetchVariablesOf(
                MODULE,
                List
                    .of(
                        new Camunda8DeploymentService.ServedElement("Loans", "ApproveLoan", "approve"),
                        new Camunda8DeploymentService.ServedElement("Cards", "ApproveCard", "approve")))
            .names(),
        "one worker serves one job type across processes, so its list has to satisfy every "
            + "method behind it");

  }

  @Test
  @DisplayName("a @TaskParam naming a variable no model mentions is fetched all the same")
  public void aParameterOutsideTheModelIsFetched() {

    final var deploymentService = deploymentService(
        bpmnProcessId -> "id",
        taskDefinition -> List.of("bigPayload"));
    wire(deploymentService, TWO_PROCESSES);

    assertEquals(
        List.of("bigPayload", "id", "vanillabpMiParents"),
        deploymentService
            .fetchVariablesOf(
                MODULE,
                List.of(new Camunda8DeploymentService.ServedElement("Loans", "ApproveLoan", "approve")))
            .names(),
        "the name comes from the method, so a value written past the model reaches the handler "
            + "without any configuration");

  }

  @Test
  @DisplayName("a method wired by the element id contributes its parameters too")
  public void theParametersOfAnIdWiredMethodAreFetched() {

    // a method carrying @WorkflowTask(id = ...) is known to the core by the element and
    // by nothing else, so the job type answers nothing about it
    final var deploymentService = deploymentService(
        bpmnProcessId -> "id",
        key -> "ApproveLoan".equals(key)
            ? List.of("bigPayload")
            : List.of());
    wire(deploymentService, TWO_PROCESSES);

    assertEquals(
        List.of("bigPayload", "id", "vanillabpMiParents"),
        deploymentService
            .fetchVariablesOf(
                MODULE,
                List.of(new Camunda8DeploymentService.ServedElement("Loans", "ApproveLoan", "approve")))
            .names(),
        "asking for the job type alone left this handler without the variable it declares, and "
            + "the worker then failed the job rather than passing null");

  }

  @Test
  @DisplayName("the workflow-end worker fetches the aggregate id and nothing the model declares")
  public void theWorkflowEndWorkerStaysAtOneVariable() {

    final var deploymentService = deploymentService(bpmnProcessId -> "id");
    wire(deploymentService, DECLARING_MODEL);

    assertEquals(
        List.of("id"),
        deploymentService
            .fetchVariablesOf(
                MODULE,
                List.of(new Camunda8DeploymentService.ServedElement("Declaring", null, null)))
            .names(),
        "a @WorkflowEnded method cannot declare a @TaskParam, so there is nothing else to fetch");

  }

  @Test
  @DisplayName("the guiding messages name what the worker fetched and no removed key")
  public void theMessagesNameTheWayOut() {

    final var selection = Camunda8FetchVariables.Selection.of(List.of("id"));

    final var missing = Camunda8FetchVariables
        .missingAggregateId("Job", 4711L, "approve", "Loans", "loanId", selection);
    assertFalse(missing.contains("fetch-variables"), "the key is gone, so no message names it: "
        + missing);
    assertTrue(missing.contains("[id]"), "the message names what the worker DID fetch, but was: "
        + missing);
    assertTrue(
        missing.contains("propagateAllParentVariables=\"false\"") && missing.contains("hands 'loanId' over"),
        "a called process which was not handed the ID is the third cause, and the message names the fix, but was: "
            + missing);

    final var unfetched = Camunda8FetchVariables.unfetchedTaskParameter("bigPayload", "approve", selection);
    assertFalse(unfetched.contains("fetch-variables"), "the key is gone, so no message names it: "
        + unfetched);
    assertTrue(unfetched.contains("bigPayload"), unfetched);
    assertTrue(
        unfetched.contains("@TaskParam(\"bigPayload\")"),
        "since the worker asks for every declared name, reaching this message means the name is "
            + "not on the method - and the message says where to put it, but was: "
            + unfetched);
    assertTrue(unfetched.contains("workflow aggregate"), "the other way out is the aggregate: "
        + unfetched);

  }

  @Test
  @DisplayName("the removed key fetch-variables ends the start, naming every key which sets it")
  public void theRemovedKeyEndsTheStart() {

    Camunda8FetchVariables.rejectTheRemovedKey("c8", List.of());

    final var keys = List.of(
        "vanillabp.adapters.c8.fetch-variables",
        "vanillabp.workflow-modules.loans.workflows.Loans.tasks.approve.adapters.c8.fetch-variables");
    final var failure = assertThrows(
        IllegalStateException.class,
        () -> Camunda8FetchVariables.rejectTheRemovedKey("c8", keys));
    final var message = failure.getMessage();
    assertTrue(message.contains("does not exist any more"), message);
    keys.forEach(key -> assertTrue(message.contains(key), "names "
        + key
        + ": "
        + message));
    assertTrue(message.contains("Remove the key"), "says what to do: "
        + message);
    assertTrue(message.contains("workflow aggregate"), "says what applies instead: "
        + message);

  }

  @Test
  @DisplayName("a selection asking for everything covers every name")
  public void everythingCoversEveryName() {

    assertTrue(Camunda8FetchVariables.Selection.everything().covers("whatever"));
    assertTrue(Camunda8FetchVariables.Selection.of(List.of("id")).covers("id"));
    assertFalse(Camunda8FetchVariables.Selection.of(List.of("id")).covers("bigPayload"));
    assertEquals(
        "all variables of the process instance",
        Camunda8FetchVariables.Selection.everything().describe());

  }

  @Test
  @DisplayName("a derived list reaches the worker builder, and 'all' leaves the builder alone")
  public void theListReachesTheWorkerBuilder() {

    final var deploymentService = deploymentService(bpmnProcessId -> "id");
    final var builder = Mockito
        .mock(JobWorkerBuilderStep1.JobWorkerBuilderStep3.class);
    Mockito
        .when(builder.fetchVariables(ArgumentMatchers.anyList()))
        .thenReturn(builder);

    deploymentService.applyFetchVariables(
        builder,
        MODULE,
        "task",
        "approve",
        Camunda8FetchVariables.Selection.of(List.of("id")));
    Mockito.verify(builder).fetchVariables(List.of("id"));

    deploymentService.applyFetchVariables(
        builder,
        MODULE,
        "start-event",
        "start",
        Camunda8FetchVariables.Selection.everything());
    // a worker naming no list is what a Camunda 8 worker does by default, so 'all' has
    // nothing to say to the builder
    Mockito.verifyNoMoreInteractions(builder);

  }

  @Test
  @DisplayName("every worker says at DEBUG what it fetches - the first question a missing variable raises")
  public void theStartupLineNamesTheList() {

    final var deploymentService = deploymentService(bpmnProcessId -> "id");
    final var builder = Mockito
        .mock(JobWorkerBuilderStep1.JobWorkerBuilderStep3.class);
    Mockito
        .when(builder.fetchVariables(ArgumentMatchers.anyList()))
        .thenReturn(builder);
    final var logWatcher = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logWatcher.start();
    final var adapterLog = (ch.qos.logback.classic.Logger) LoggerFactory
        .getLogger(Camunda8DeploymentService.class);
    final var previousLevel = adapterLog.getLevel();
    adapterLog.setLevel(ch.qos.logback.classic.Level.DEBUG);
    adapterLog.addAppender(logWatcher);
    try {
      deploymentService.applyFetchVariables(
          builder,
          MODULE,
          "task",
          "approve",
          Camunda8FetchVariables.Selection.of(List.of("id")));
    } finally {
      adapterLog.detachAppender(logWatcher);
      adapterLog.setLevel(previousLevel);
    }

    assertTrue(
        logWatcher.list
            .stream()
            .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.DEBUG)
            .anyMatch(event -> event.getFormattedMessage().contains("the task worker 'approve'") && event
                .getFormattedMessage().contains("[id]")),
        () -> "expected one line per worker naming its list, but saw: "
            + logWatcher.list);

  }

  /**
   * Decomposition: the multi-instance subprocess is in the CALLER, the task asking for its
   * iteration is in the called process.
   */
  private static final String CALLER_AND_CALLED = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="Orders" isExecutable="true">
          <bpmn:subProcess id="PerItem">
            <bpmn:multiInstanceLoopCharacteristics>
              <bpmn:extensionElements>
                <zeebe:loopCharacteristics inputCollection="=items" inputElement="item" />
              </bpmn:extensionElements>
            </bpmn:multiInstanceLoopCharacteristics>
            <bpmn:callActivity id="Deliver">
              <bpmn:extensionElements>
                <zeebe:calledElement processId="Delivery" />
              </bpmn:extensionElements>
            </bpmn:callActivity>
          </bpmn:subProcess>
        </bpmn:process>
        <bpmn:process id="Delivery" isExecutable="true">
          <bpmn:serviceTask id="Pack">
            <bpmn:extensionElements>
              <zeebe:taskDefinition type="pack" />
            </bpmn:extensionElements>
          </bpmn:serviceTask>
        </bpmn:process>
      </bpmn:definitions>
      """;

  @Test
  @DisplayName("a worker of a called process asks for the multi-instance variables of the call site")
  public void theListFollowsTheChainAcrossTheProcessBoundary() {

    final var deploymentService = deploymentService(bpmnProcessId -> "id");
    final var context = wire(deploymentService, CALLER_AND_CALLED);
    deploymentService.wireTheProcessesThisModuleCalls(MODULE, context);

    assertEquals(
        List
            .of(
                "id",
                "vanillabpMiElement_PerItem",
                "vanillabpMiIndex_PerItem",
                "vanillabpMiParents",
                "vanillabpMiTotal_PerItem"),
        deploymentService
            .fetchVariablesOf(
                MODULE,
                List.of(new Camunda8DeploymentService.ServedElement("Delivery", "Pack", "pack")))
            .names(),
        "the fetch list is built from the same chain, so it needs nothing of its own - without "
            + "these names the cluster would not even send what it already holds");

  }

  @Test
  @DisplayName("without the call graph the same worker asks for the aggregate id alone")
  public void theListIsEmptyUntilTheCallGraphIsBuilt() {

    final var deploymentService = deploymentService(bpmnProcessId -> "id");
    wire(deploymentService, CALLER_AND_CALLED);

    assertEquals(
        List.of("id", "vanillabpMiParents"),
        deploymentService
            .fetchVariablesOf(
                MODULE,
                List.of(new Camunda8DeploymentService.ServedElement("Delivery", "Pack", "pack")))
            .names(),
        "which is what this adapter did before the chain crossed the boundary, apart from the "
            + "one name every worker serving an element carries");

  }


  @Test
  @DisplayName("a called process with a workflow aggregate of its own is told no iteration of its caller")
  public void aProcessOfItsOwnStaysOutsideTheChain() {

    final var deploymentService = deploymentService(
        bpmnProcessId -> "id",
        taskDefinition -> List.of(),
        (
            caller,
            called) -> false);
    final var context = wire(deploymentService, CALLER_AND_CALLED);
    deploymentService.wireTheProcessesThisModuleCalls(MODULE, context);

    assertTrue(
        deploymentService
            .multiInstanceRegistry()
            .chainOf("Delivery", "Pack")
            .isEmpty(),
        "the cluster still copies the caller's variables into the instance, and this adapter "
            + "reports none of them - which is the only place the line can honestly be drawn");
    assertEquals(
        List.of("id", "vanillabpMiParents"),
        deploymentService
            .fetchVariablesOf(
                MODULE,
                List.of(new Camunda8DeploymentService.ServedElement("Delivery", "Pack", "pack")))
            .names(),
        "and the chain variable brings nothing either, because the reader drops an entry whose "
            + "caller does not share this aggregate");

  }

  @Test
  @DisplayName("a worker reporting a whole process rather than an element asks for the aggregate id alone")
  public void theWorkflowEndListenerAsksForOneName() {

    final var deploymentService = deploymentService(bpmnProcessId -> "id");
    wire(deploymentService, TWO_PROCESSES);

    assertEquals(
        List.of("id"),
        deploymentService
            .fetchVariablesOf(MODULE, List.of(new Camunda8DeploymentService.ServedElement("Loans", null, null)))
            .names(),
        "a @WorkflowEnded method reports no iteration and cannot declare a @TaskParam, so the "
            + "chain of a caller would be payload it never reads");

  }

}
