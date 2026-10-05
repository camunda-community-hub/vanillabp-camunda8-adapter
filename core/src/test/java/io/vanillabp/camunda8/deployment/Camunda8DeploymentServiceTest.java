package io.vanillabp.camunda8.deployment;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

import io.camunda.client.api.worker.JobWorkerBuilderStep1;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.vanillabp.camunda8.Camunda8ProcessingContext;
import io.vanillabp.camunda8.TestCollaborators;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.camunda8.wiring.Camunda8MultiInstance;
import io.vanillabp.camunda8.wiring.Camunda8Scoping;
import io.vanillabp.camunda8.wiring.Camunda8TaskWiring;
import io.vanillabp.integration.adapter.spi.AggregateSyncMode;
import io.vanillabp.integration.adapter.spi.BpmnParseException;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Unit tests of {@link Camunda8DeploymentService} not requiring a cluster: BPMN parsing
 * (executable-process extraction, parse errors) and that deployment of an empty module
 * does not touch the client.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8DeploymentServiceTest {

  private static final String TWO_PROCESSES_ONE_EXECUTABLE = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
          id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="ExecutableProcess" isExecutable="true">
          <bpmn:startEvent id="start"/>
        </bpmn:process>
        <bpmn:process id="NonExecutableProcess" isExecutable="false">
          <bpmn:startEvent id="start2"/>
        </bpmn:process>
      </bpmn:definitions>
      """;

  private static final String TWO_EXECUTABLE_PROCESSES = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
          id="Definitions_2" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="First" isExecutable="true">
          <bpmn:startEvent id="start1"/>
        </bpmn:process>
        <bpmn:process id="Second" isExecutable="true">
          <bpmn:startEvent id="start2"/>
        </bpmn:process>
      </bpmn:definitions>
      """;

  private static final String CREDIT_RATING_DMN = """
      <?xml version="1.0" encoding="UTF-8"?>
      <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/"
          id="creditRatingDefinitions" name="Credit rating" namespace="http://vanillabp.io/test">
        <decision id="creditRating" name="Credit rating">
          <decisionTable id="creditRatingTable" hitPolicy="UNIQUE"/>
        </decision>
      </definitions>
      """;

  private Camunda8DeploymentService newDeploymentService() {

    // an unconfigured factory: getClient() would throw if ever called
    return DeploymentServiceUnderTest.of("c8", new Camunda8ClientFactory("c8", new Camunda8AdapterConfiguration()),
        TestCollaborators
            .of(new NoOpInvoker()),
        (
            m2,
            p2,
            t2) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
        Duration
            .ofDays(14));

  }

  private static InputStream bpmn(
      final String bpmnProcessId) {

    final var model = Bpmn
        .createExecutableProcess(bpmnProcessId)
        .startEvent()
        .endEvent()
        .done();
    return new ByteArrayInputStream(Bpmn.convertToString(model).getBytes(UTF_8));

  }

  @Test
  @DisplayName("readBpmn returns one entry per executable process")
  public void readBpmnReturnsExecutableProcess() {

    final var service = newDeploymentService();

    final var result = service.readBpmn("module", "test.bpmn", bpmn("MyProcess"), true);

    assertEquals(1, result.size());
    assertEquals("MyProcess", result.get(0).getKey());

  }

  @Test
  @DisplayName("readBpmn ignores non-executable processes of a multi-process file")
  public void readBpmnIgnoresNonExecutableProcesses() {

    final var service = newDeploymentService();

    final var result = service.readBpmn(
        "module",
        "two.bpmn",
        new ByteArrayInputStream(TWO_PROCESSES_ONE_EXECUTABLE.getBytes(UTF_8)),
        true);

    assertEquals(1, result.size());
    assertEquals("ExecutableProcess", result.get(0).getKey());

  }

  @Test
  @DisplayName("readBpmn wraps parse errors in BpmnParseException")
  public void readBpmnWrapsParseErrors() {

    final var service = newDeploymentService();

    final var exception = assertThrows(
        BpmnParseException.class,
        () -> service.readBpmn(
            "module",
            "broken.bpmn",
            new ByteArrayInputStream("this is not valid bpmn xml".getBytes(UTF_8)),
            true));
    assertTrue(exception.getMessage().contains("broken.bpmn"));

  }

  @Test
  @DisplayName("prepareBpmn accumulates resources and process IDs into the context")
  public void prepareBpmnAccumulates() {

    final var service = newDeploymentService();
    final var model = Bpmn
        .createExecutableProcess("P1")
        .startEvent()
        .endEvent()
        .done();

    final Camunda8ProcessingContext context = service.prepareBpmn("module", null, "a.bpmn", "P1", model);
    // a second executable process of the same file reuses the context and deduplicates
    // the resource by filename
    service.prepareBpmn("module", context, "a.bpmn", "P2", model);

    assertEquals(1, context.getResources().size());

  }

  @Test
  @DisplayName("the context names the adapter and the workflow module the pipeline runs for")
  public void theContextNamesItsAdapterAndItsWorkflowModule() {

    final var service = newDeploymentService();
    final var model = Bpmn
        .createExecutableProcess("P1")
        .startEvent()
        .endEvent()
        .done();

    final var context = service.prepareBpmn("module", null, "a.bpmn", "P1", model);

    // an extension of the pipeline sees this context and nothing else of the run, so
    // without these two it cannot tell one adapter's call from another's - and every
    // configured Camunda 8 adapter makes the same call with the same file
    assertEquals("c8", context.getAdapterId());
    assertEquals("module", context.getWorkflowModuleId());
    // and it carries the registry the adapter wires its models into, so an extension which
    // wants to name the iteration a task belongs to asks rather than reading the models again
    assertSame(
        service.multiInstanceRegistry(),
        context.getMultiInstanceRegistry(),
        "the adapter's own registry, not one of the context's");

  }

  @Test
  @DisplayName("deploying an empty module does not touch the (unconfigured) client")
  public void deployEmptyModuleDoesNotTouchClient() {

    final var service = newDeploymentService();

    // null context (no BPMN files at all) and empty context must not build a client
    assertDoesNotThrow(() -> service.deployResources("module", null));
    assertDoesNotThrow(() -> service.deployResources("module",
        new Camunda8ProcessingContext("c8", "module", new Camunda8MultiInstance.Registry())));

  }


  /**
   * Wiring validation is exercised by the integration tests - unit tests use a
   * permissive no-op invoker.
   */

  @Test
  @DisplayName("a listener worker's lock is resolved like a task's, defaulting to the same five minutes")
  public void listenerLockDefaultsToTheJobTimeout() {

    final var deploymentService = newDeploymentService();

    assertEquals(
        Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
        deploymentService.listenerLockOf("m", List.of("Process"), "workflow-end", "jobType"),
        "the user-task listener, the BPMS-initiated start and the workflow end run application code "
            + "in a transaction just like a task, so there is no second rule");

  }

  @Test
  @DisplayName("a listener worker's lock follows the configured job-timeout of its workflow")
  public void listenerLockFollowsTheConfiguredJobTimeout() {

    final var deploymentService = DeploymentServiceUnderTest.of(
        "c8", new Camunda8ClientFactory("c8", new Camunda8AdapterConfiguration()), TestCollaborators
            .of(new NoOpInvoker()),
        (
            workflowModuleId,
            bpmnProcessId,
            taskDefinition) -> "Process".equals(bpmnProcessId)
                ? Duration.ofMinutes(2)
                : Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
        Duration
            .ofDays(14));

    assertEquals(
        Duration.ofMinutes(2),
        deploymentService.listenerLockOf("m", List.of("Process"), "start-event", "jobType"),
        "there is no task to key a listener by, so it resolves at adapter, module and workflow level");

  }

  @Test
  @DisplayName("one listener job type with conflicting locks fails naming both processes")
  public void conflictingListenerLocksFailGuiding() {

    final var deploymentService = DeploymentServiceUnderTest.of(
        "c8", new Camunda8ClientFactory("c8", new Camunda8AdapterConfiguration()), TestCollaborators
            .of(new NoOpInvoker()),
        (
            workflowModuleId,
            bpmnProcessId,
            taskDefinition) -> "Fast".equals(bpmnProcessId)
                ? Duration.ofSeconds(30)
                : Duration.ofMinutes(10),
        Duration.ofDays(14));

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> deploymentService.listenerLockOf(
            "m", List.of("Fast", "Slow"), "user-task listener", "io.vanillabp.userTask:form"));

    assertTrue(exception.getMessage().contains("Fast"), exception.getMessage());
    assertTrue(exception.getMessage().contains("Slow"), exception.getMessage());
    assertTrue(exception.getMessage().contains("job-timeout"),
        "the message names the property to align, but was: "
            + exception.getMessage());

  }


  @Test
  @DisplayName("one task definition with conflicting job timeouts fails naming both")
  public void conflictingTaskLocksFailGuiding() {

    // the lock of a worker is the lock of the task definition it subscribes to, so two
    // tasks of one definition resolving differently leave the worker without an answer.
    // What makes them differ is configuration, which the resolver stands in for: it
    // answers the first task thirty seconds and the second one ten minutes
    final var locks = List.of(Duration.ofSeconds(30), Duration.ofMinutes(10)).iterator();
    final var configuration = new Camunda8AdapterConfiguration();
    // a client is built and the query API is asked once before the workers are opened;
    // an address nothing listens on answers both of those without a cluster
    configuration.setRestAddress("http://localhost:1");
    try (final var clientFactory = new Camunda8ClientFactory("c8", configuration)) {

      final var deploymentService = DeploymentServiceUnderTest.of(
          "c8", clientFactory, TestCollaborators
              .of(new NoOpInvoker()),
          (
              workflowModuleId,
              bpmnProcessId,
              taskDefinition) -> locks.next(),
          Duration.ofDays(14));
      final var context = new Camunda8ProcessingContext("c8", "m", new Camunda8MultiInstance.Registry());
      context
          .getTasksToWire()
          .add(new Camunda8TaskWiring.Camunda8TaskToWire("Fast", "assessRisk", "assessRisk"));
      context
          .getTasksToWire()
          .add(new Camunda8TaskWiring.Camunda8TaskToWire("Slow", "assessRisk", "assessRisk"));

      final var exception = assertThrows(
          IllegalStateException.class,
          () -> deploymentService.startWorkflowProcessing("m", context));

      assertTrue(exception.getMessage().contains("assessRisk"),
          "the message names the task definition, but was: "
              + exception.getMessage());
      assertTrue(exception.getMessage().contains("PT30S"),
          "the message names both timeouts, but was: "
              + exception.getMessage());
      assertTrue(exception.getMessage().contains("PT10M"),
          "the message names both timeouts, but was: "
              + exception.getMessage());
      assertTrue(exception.getMessage().contains("job-timeout"),
          "the message names the property to align, but was: "
              + exception.getMessage());

    }

  }


  @Test
  @DisplayName("stream-timeout is the one worker option with no client-wide equivalent, so it is set per worker")
  public void streamTimeoutReachesTheWorkerBuilder() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setStreamTimeout(Duration.ofMinutes(30));
    final var deploymentService = DeploymentServiceUnderTest.of(
        "c8", new Camunda8ClientFactory("c8", configuration), TestCollaborators
            .of(new NoOpInvoker()),
        (
            m,
            p,
            t) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
        Duration
            .ofDays(14));
    final var builder = Mockito
        .mock(JobWorkerBuilderStep1.JobWorkerBuilderStep3.class);
    Mockito
        .when(builder.metrics(ArgumentMatchers.any()))
        .thenReturn(builder);
    Mockito
        .when(builder.streamTimeout(ArgumentMatchers.any()))
        .thenReturn(builder);

    deploymentService.applyWorkerOptions(builder, "approve");

    Mockito
        .verify(builder)
        .streamTimeout(Duration.ofMinutes(30));

  }

  @Test
  @DisplayName("without a configured stream-timeout the worker keeps the client's")
  public void withoutStreamTimeoutTheWorkerIsLeftAlone() {

    final var deploymentService = newDeploymentService();
    final var builder = Mockito
        .mock(JobWorkerBuilderStep1.JobWorkerBuilderStep3.class);
    Mockito
        .when(builder.metrics(ArgumentMatchers.any()))
        .thenReturn(builder);

    assertNotNull(deploymentService.applyWorkerOptions(builder, "approve"));

    // The metrics hook is the ONE thing every worker gets, and without a
    // metrics backend it is the client's own no-op
    Mockito
        .verify(builder)
        .metrics(ArgumentMatchers.any());
    Mockito.verifyNoMoreInteractions(builder);

  }

  /**
   * Plays both halves of the split task SPI: the deployment service wires through
   * {@code WorkflowTaskWiring} and opens its job workers with
   * {@code WorkflowTaskInvoker}, so a double standing in for the core answers both.
   */
  static class NoOpInvoker implements WorkflowTaskWiring, WorkflowTaskInvoker {

    @Override
    public void validateTaskWiring(
        final String workflowModuleId,
        final String bpmnProcessId,
        final Collection<BpmnTaskSpec> tasks) {
    }

    @Override
    public void validateNoUnwiredWorkflowTaskMethods(
        final String workflowModuleId) {
    }

    @Override
    public WorkflowTaskOutcome invokeWorkflowTask(
        final String workflowModuleId,
        final String bpmnProcessId,
        final TaskInvocationContext context) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, Object> syncedWorkflowAggregateValues(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId,
        final AggregateSyncMode adapterDefault) {
      return Map.of();
    }

    // the migration fallback, deprecated for removal and none of Camunda
    // 8's business: a test double implements it as long as the interface declares it,
    // and the mandatory 'removal' lint needs the suppression
    @SuppressWarnings("removal")
    @Override
    public boolean workflowAggregateHasProperty(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String propertyName) {
      return false;
    }

    @SuppressWarnings("removal")
    @Override
    public Object resolveWorkflowAggregateProperty(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId,
        final String propertyName) {
      return null;
    }

    @Override
    public boolean workflowTaskHandlerExists(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String taskDefinitionOrActivityId) {
      return true;
    }

    @Override
    public String resolveWorkflowAggregateIdName(
        final String workflowModuleId,
        final String bpmnProcessId) {
      return "id";
    }

  }


  /**
   * The tenant a workflow module is deployed to, and the prefixed
   * identifiers replacing it. Asserted on the model and the resolved tenant - the
   * cluster round-trip is covered by the Docker ITs.
   */
  @Nested
  @DisplayName("Name-clash avoidance")
  class NameClashAvoidanceTests {

    private static final String MODULE = "loan-approval";

    /**
     * A minimal {@link NameClashAvoidanceSupport}
     * of the given mode - the adapter is tested against the SPI, not against the
     * core's implementation (this module deliberately depends on the SPI only).
     */
    private NameClashAvoidanceSupport scopingWith(
        final NameClashAvoidance mode) {

      final var separator = NameClashAvoidanceSupport.SEPARATOR;
      return new NameClashAvoidanceSupport() {

        private boolean prefixes() {
          return mode == NameClashAvoidance.USE_PREFIX;
        }

        @Override
        public NameClashAvoidance modeFor(
            final String workflowModuleId,
            final String bpmnProcessId,
            final String adapterId) {
          return mode;
        }

        @Override
        public String scopedProcessId(
            final String workflowModuleId,
            final String bpmnProcessId,
            final String adapterId) {
          return prefixes()
              ? workflowModuleId + separator + bpmnProcessId
              : bpmnProcessId;
        }

        @Override
        public String scopedIdentifier(
            final String workflowModuleId,
            final String identifier,
            final String adapterId) {
          return prefixes() && (identifier != null)
              ? workflowModuleId + separator + identifier
              : identifier;
        }

        @Override
        public String scopedTaskDefinition(
            final String workflowModuleId,
            final String bpmnProcessId,
            final String taskDefinition,
            final String adapterId) {
          return prefixes() && (taskDefinition != null)
              ? workflowModuleId + separator + bpmnProcessId + separator + taskDefinition
              : taskDefinition;
        }

        @Override
        public String plainProcessId(
            final String workflowModuleId,
            final String scopedBpmnProcessId,
            final String adapterId) {
          final var prefix = workflowModuleId + separator;
          return prefixes() && scopedBpmnProcessId.startsWith(prefix)
              ? scopedBpmnProcessId.substring(prefix.length())
              : scopedBpmnProcessId;
        }

        @Override
        public String plainIdentifier(
            final String workflowModuleId,
            final String scopedIdentifier,
            final String adapterId) {
          return plainProcessId(workflowModuleId, scopedIdentifier, adapterId);
        }

        @Override
        public String plainTaskDefinition(
            final String workflowModuleId,
            final String bpmnProcessId,
            final String scopedTaskDefinition,
            final String adapterId) {
          final var prefix = workflowModuleId + separator + bpmnProcessId + separator;
          return prefixes() && scopedTaskDefinition.startsWith(prefix)
              ? scopedTaskDefinition.substring(prefix.length())
              : scopedTaskDefinition;
        }

        @Override
        public void validateNoneNameClashStrategy(
            final String adapterId,
            final String byAdapterOnlyPropertyKey) {
        }

        @Override
        public void validateNativeIsolationSupported(
            final String adapterId,
            final String workflowModuleId,
            final String bpmsDescription) {
        }

        // the three reports of a name a BPMS already holds: this double measures the model
        // and the tenant, and the tests which read those reports use TestScoping instead
        @Override
        public void reportIdentifiersTheBpmsAlreadyHolds(
            final String adapterId,
            final String workflowModuleId,
            final Collection<IdentifierHeldElsewhere> found) {
        }

        @Override
        public void reportIdentifiersTheModelsDeclare(
            final String adapterId,
            final String workflowModuleId,
            final Collection<ModelIdentifier> declared) {
        }

        @Override
        public void reportIdentifiersOfHeldVersion(
            final String adapterId,
            final String workflowModuleId,
            final String bpmnProcessId,
            final String version,
            final Long activeWorkflows,
            final Collection<ModelIdentifier> declared) {
        }

        @Override
        public void validateNoCollidingProcessIds(
            final String adapterId,
            final Collection<DeployedProcess> deployedProcesses) {
        }

      };

    }

    @Test
    @DisplayName("a decision and the business rule task calling it are renamed to the same string")
    public void aBusinessRuleTaskFindsItsRenamedDecision() {

      final var scoping = scopingWith(NameClashAvoidance.USE_PREFIX);
      final var service = DeploymentServiceUnderTest.of(
          "c8", new Camunda8ClientFactory("c8", new Camunda8AdapterConfiguration()), TestCollaborators
              .of(new NoOpInvoker()),
          (
              m,
              p,
              t) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
          Duration.ofDays(14), null, scoping);

      final var model = Bpmn
          .createExecutableProcess("Rating")
          .startEvent()
          .businessRuleTask("rate", task -> task.zeebeCalledDecisionId("creditRating").zeebeResultVariable("rating"))
          .endEvent()
          .done();
      final var context = service.prepareBpmn(MODULE, null, "rating.bpmn", "Rating", model);
      service
          .readDmn(MODULE, context, "rating.dmn", new ByteArrayInputStream(CREDIT_RATING_DMN.getBytes(UTF_8)));

      // what the model points at has to be what the cluster deploys the decision under,
      // so both sides go through the same function of the core
      final var calledDecision = model
          .getModelElementsByType(io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeCalledDecision.class)
          .iterator()
          .next()
          .getDecisionId();
      final var deployedDecisionId = io.vanillabp.integration.adapter.spi.DmnDecisionIds
          .of(context.getDecisions().get("rating.dmn"))
          .iterator()
          .next();
      assertEquals(deployedDecisionId, calledDecision);
      assertEquals(MODULE
          + NameClashAvoidanceSupport.SEPARATOR
          + "creditRating", calledDecision);

    }

    @Test
    @DisplayName("without prefixing the decision keeps the id the modeller gave it")
    public void byAdapterLeavesTheDecisionAlone() {

      final var scoping = scopingWith(NameClashAvoidance.BY_ADAPTER);
      final var service = DeploymentServiceUnderTest.of(
          "c8", new Camunda8ClientFactory("c8", new Camunda8AdapterConfiguration()), TestCollaborators
              .of(new NoOpInvoker()),
          (
              m,
              p,
              t) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
          Duration.ofDays(14), null, scoping);

      final var model = Bpmn
          .createExecutableProcess("Rating")
          .startEvent()
          .businessRuleTask("rate", task -> task.zeebeCalledDecisionId("creditRating").zeebeResultVariable("rating"))
          .endEvent()
          .done();
      final var context = service.prepareBpmn(MODULE, null, "rating.bpmn", "Rating", model);
      service
          .readDmn(MODULE, context, "rating.dmn", new ByteArrayInputStream(CREDIT_RATING_DMN.getBytes(UTF_8)));

      assertEquals(
          "creditRating",
          model
              .getModelElementsByType(io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeCalledDecision.class)
              .iterator()
              .next()
              .getDecisionId());
      assertEquals(
          List.of("creditRating"),
          List.copyOf(
              io.vanillabp.integration.adapter.spi.DmnDecisionIds
                  .of(context.getDecisions().get("rating.dmn"))));

    }

    @Test
    @DisplayName("a business rule task calling a decision is no job-worker task")
    public void aDecisionIsServedByTheClusterAndNotByAWorker() {

      final var callingADecision = Bpmn
          .createExecutableProcess("Rating")
          .startEvent()
          .businessRuleTask("rate", task -> task.zeebeCalledDecisionId("creditRating").zeebeResultVariable("rating"))
          .endEvent()
          .done();

      assertEquals(
          List.of(),
          io.vanillabp.camunda8.wiring.Camunda8TaskWiring.tasksOf(callingADecision, "Rating", false),
          "the cluster evaluates the decision, so no @WorkflowTask method is expected");

      // one wired by a task definition is an ordinary VanillaBP task
      final var callingAWorker = Bpmn
          .createExecutableProcess("Rating")
          .startEvent()
          .businessRuleTask("rate", task -> task.zeebeJobType("rateTheApplicant"))
          .endEvent()
          .done();

      assertEquals(
          1,
          io.vanillabp.camunda8.wiring.Camunda8TaskWiring.tasksOf(callingAWorker, "Rating", false).size());

    }

    private BpmnModelInstance modelOf(
        final NameClashAvoidance mode) {

      final var service = DeploymentServiceUnderTest.of(
          "c8", new Camunda8ClientFactory("c8", new Camunda8AdapterConfiguration()), TestCollaborators
              .of(new NoOpInvoker()),
          (
              m2,
              p2,
              t2) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
          Duration
              .ofDays(14),
          null, scopingWith(mode));
      final var model = Bpmn
          .createExecutableProcess("RiskAssessment")
          .startEvent()
          .serviceTask("Task", task -> task.zeebeJobType("scoreApplicant"))
          .endEvent()
          .done();
      service.prepareBpmn(MODULE, null, "risk.bpmn", "RiskAssessment", model);
      return model;

    }

    @Test
    @DisplayName("BY_ADAPTER leaves the model alone - the TENANT isolates, as in version 1")
    public void byAdapterKeepsTheModelAndUsesTheModuleAsTenant() {

      final var model = modelOf(NameClashAvoidance.BY_ADAPTER);

      assertNotNull(model.getModelElementById("RiskAssessment"), "the process id stays plain");
      // the tenant a module is deployed to: the workflow module id (version 1's
      // behavior, overridable by the adapter's tenant-id)
      assertEquals(
          MODULE,
          Camunda8Scoping.tenantIdFor(
              scopingWith(NameClashAvoidance.BY_ADAPTER), MODULE, "c8", null));
      // ... unless the adapter configured a name
      assertEquals(
          "banking",
          Camunda8Scoping.tenantIdFor(
              scopingWith(NameClashAvoidance.BY_ADAPTER), MODULE, "c8",
              "banking"));

    }

    @Test
    @DisplayName("USE_PREFIX rewrites process id and job type - and uses NO tenant")
    public void usePrefixRewritesTheModel() {

      final var model = modelOf(NameClashAvoidance.USE_PREFIX);

      assertNotNull(
          model.getModelElementById("loan-approval__RiskAssessment"),
          "the process id carries the workflow module as prefix");
      final var xml = Bpmn.convertToString(model);
      assertTrue(
          xml.contains("loan-approval__RiskAssessment__scoreApplicant"),
          () -> "the job type is scoped per module AND process but was: "
              + xml);
      assertNull(
          Camunda8Scoping.tenantIdFor(
              scopingWith(NameClashAvoidance.USE_PREFIX), MODULE, "c8",
              "banking"),
          "the prefix IS the isolation - no tenant, which is what saves tenant licenses");

    }

    @Test
    @DisplayName("a multi-process file is prefixed ONCE, although prepareBpmn is called per process")
    public void multiProcessFileIsScopedOnlyOnce() {

      final var service = DeploymentServiceUnderTest.of(
          "c8", new Camunda8ClientFactory("c8", new Camunda8AdapterConfiguration()), TestCollaborators
              .of(new NoOpInvoker()),
          (
              m2,
              p2,
              t2) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
          Duration
              .ofDays(
                  14),
          null, scopingWith(NameClashAvoidance.USE_PREFIX));
      final var model = Bpmn
          .readModelFromStream(new ByteArrayInputStream(TWO_EXECUTABLE_PROCESSES.getBytes(UTF_8)));

      // this is what the core does for a file holding two executable processes: one
      // prepareBpmn call per PROCESS, both sharing the very same model instance
      var context = service.prepareBpmn(MODULE, null, "two.bpmn", "First", model);
      context = service.prepareBpmn(MODULE, context, "two.bpmn", "Second", model);

      assertNotNull(model.getModelElementById("loan-approval__First"), "prefixed exactly once");
      assertNotNull(model.getModelElementById("loan-approval__Second"), "prefixed exactly once");
      assertNull(
          model.getModelElementById("loan-approval__loan-approval__First"),
          "the second prepareBpmn call must not prefix the model again");

    }

    @Test
    @DisplayName("NONE leaves the model alone and uses no tenant")
    public void noneScopesNothing() {

      final var model = modelOf(NameClashAvoidance.NONE);

      assertNotNull(model.getModelElementById("RiskAssessment"));
      assertNull(
          Camunda8Scoping.tenantIdFor(
              scopingWith(NameClashAvoidance.NONE), MODULE, "c8", null));

    }

    private Camunda8DeploymentService serviceOfAdapterId(
        final String adapterId) {

      return DeploymentServiceUnderTest.of(
          adapterId, new Camunda8ClientFactory(adapterId, new Camunda8AdapterConfiguration()), TestCollaborators
              .of(new NoOpInvoker()),
          (
              m2,
              p2,
              t2) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
          Duration
              .ofDays(14),
          null, null);

    }

    @Test
    @DisplayName("Without configuration the mode is BY_ADAPTER - what version 1 deployed")
    public void defaultsToByAdapter() {

      assertEquals(
          NameClashAvoidance.BY_ADAPTER,
          serviceOfAdapterId("c8").defaultNameClashAvoidance(),
          "version 1 deployed every workflow module into a tenant named after it, so an "
              + "application upgrading without touching its configuration has to keep addressing "
              + "the workflows it started back then; a cluster without multi-tenancy says so and "
              + "names both ways out (Camunda8TenantCheck)");

    }

    /**
     * The WARNs the adapter logged (the module's logback-test.xml has no appender on
     * purpose).
     */
    private List<String> warningsOf(
        final Runnable action) {

      final var logWatcher = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
      logWatcher.start();
      final var adapterLog = (ch.qos.logback.classic.Logger) LoggerFactory
          .getLogger(Camunda8DeploymentService.class);
      adapterLog.addAppender(logWatcher);
      try {
        action.run();
      } finally {
        adapterLog.detachAndStopAllAppenders();
      }
      return logWatcher.list
          .stream()
          .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
          .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
          .toList();

    }

    @Test
    @DisplayName("An unscoped workflow module is reported naming Camunda 8's alternatives")
    public void unscopedIdentifiersAreReported() {

      final var service = serviceOfAdapterId("myengine");

      final var byDefault = warningsOf(() -> service.warnAboutUnscopedIdentifiers(MODULE, true));
      assertEquals(1, byDefault.size(), byDefault::toString);
      final var message = byDefault.getFirst();
      assertTrue(message.contains("'"
          + MODULE
          + "'"), () -> message);
      assertTrue(message.contains("nothing is configured"), () -> message);
      assertTrue(
          message.contains("vanillabp.adapters.myengine.name-clash-avoidance: use-prefix"),
          () -> message);
      assertTrue(
          message.contains("vanillabp.adapters.myengine.name-clash-avoidance: by-adapter"),
          () -> message);
      assertTrue(message.contains("multi-tenancy"), () -> message);
      assertTrue(message.contains("cluster per workflow module"), () -> message);

      // a configured 'none' is reported as the deliberate choice it is
      final var configured = warningsOf(() -> service.warnAboutUnscopedIdentifiers(MODULE, false));
      assertTrue(!configured.getFirst().contains("nothing is configured"), configured::toString);
      // ... and the way out of the warning is part of it
      assertTrue(
          configured.getFirst().contains("vanillabp.adapters.myengine.accept-unscoped-identifiers: true"),
          configured::toString);

    }

    @Test
    @DisplayName("Accepting unscoped identifiers deliberately silences the warning")
    public void acceptedUnscopedIdentifiersStaySilent() {

      final var configuration = new Camunda8AdapterConfiguration();
      configuration.setAcceptUnscopedIdentifiers(true);
      final var service = DeploymentServiceUnderTest.of(
          "myengine", new Camunda8ClientFactory("myengine", configuration), TestCollaborators
              .of(new NoOpInvoker()),
          (
              m2,
              p2,
              t2) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
          Duration
              .ofDays(14),
          null, null);

      assertEquals(
          List.of(),
          warningsOf(() -> service.warnAboutUnscopedIdentifiers(MODULE, true)),
          "the decision is on record, so there is nothing left to ask");

    }

  }

}
