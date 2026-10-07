package io.vanillabp.camunda8.deployment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.vanillabp.camunda8.TestCollaborators;
import io.vanillabp.camunda8.TestScoping;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.wiring.Camunda8AllowListenersResolver;
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a boot says about the listeners somebody modelled: nothing where there are none, a
 * refusal where nobody asked for them, and one framed report where somebody did.
 * <p>
 * A listener whose job type no method of this application names is the other half, and who
 * claims the process decides it. In the process of a {@code @WorkflowService} class it goes to
 * the core's wiring validation as a task, which asks for a method or for
 * {@code implemented-externally=true}. A process nobody claims keeps its WARN, and so does a
 * listener on a job element built from an element template: that template is how a developer
 * says the element belongs to somebody else. On a user task a template does not count.
 * <p>
 * The cluster is an address nothing listens on. What is under test is the text a reader gets
 * and the task specs the core is handed, and neither needs a cluster.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda8ListenersReportTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  private static final String FILE = "loan-approval.bpmn";

  private static BpmnModelInstance modelWithAListener() {

    return model("""
            <bpmn:serviceTask id="Activity_Approve">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="approve" />
              </bpmn:extensionElements>
            </bpmn:serviceTask>
            <bpmn:endEvent id="Event_Done">
              <bpmn:extensionElements>
                <zeebe:executionListeners>
                  <zeebe:executionListener eventType="end" type="archiveTheOrder" />
                </zeebe:executionListeners>
              </bpmn:extensionElements>
            </bpmn:endEvent>
        """);

  }

  private static BpmnModelInstance modelWithoutAListener() {

    return model("""
            <bpmn:serviceTask id="Activity_Approve">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="approve" />
              </bpmn:extensionElements>
            </bpmn:serviceTask>
        """);

  }

  private static BpmnModelInstance modelWithTwoListenersUnderOneJobType() {

    return model("""
            <bpmn:serviceTask id="Activity_Approve">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="approve" />
                <zeebe:executionListeners>
                  <zeebe:executionListener eventType="start" type="auditTheApproval" />
                  <zeebe:executionListener eventType="end" type="auditTheApproval" />
                </zeebe:executionListeners>
              </bpmn:extensionElements>
            </bpmn:serviceTask>
        """);

  }

  private static BpmnModelInstance model(
      final String processContent) {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="%s" isExecutable="true">
        %s
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(PROCESS, processContent);
    return Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

  }

  /**
   * Runs the pipeline stages which fill the module's report, and writes it.
   */
  private static String deploy(
      final CapturedOutput output,
      final Camunda8DeploymentService service,
      final BpmnModelInstance model) {

    // what THIS test logged: the capture spans the whole class, and every case here
    // asserts the absence of something an earlier case wrote
    final var before = output.getAll().length();
    final var context = service.prepareBpmn(MODULE, null, FILE, PROCESS, model);
    service.wireBpmn(MODULE, FILE, PROCESS, model, context);
    service.reportWhatListenersCost(MODULE, context);
    return output.getAll().substring(before);

  }

  @Test
  @DisplayName("A module whose listeners are served gets a framed report naming key, module and listeners")
  public void theReportNamesTheListeners(
      final CapturedOutput output) {

    final var logged = deploy(
        output,
        adapter(NameClashAvoidance.BY_ADAPTER, allowedBy("vanillabp.adapters.c8.allow-listeners")),
        modelWithAListener());

    assertTrue(
        logged.contains("MODELLED LISTENERS ARE SERVED: WORKFLOW MODULE 'loan-approval'"),
        () -> "a heading a reader finds without reading a word: "
            + logged);
    assertTrue(logged.contains("===================="), () -> "and the frame around it: "
        + logged);
    assertTrue(
        logged.contains("vanillabp.adapters.c8.allow-listeners"),
        () -> "the key which switched it on: "
            + logged);
    assertTrue(
        logged.contains("element 'Event_Done'") && logged.contains("job type 'archiveTheOrder'"),
        () -> "the element and the job type a modeller recognises it by: "
            + logged);
    assertTrue(
        logged.contains("execution listener on 'end'"),
        () -> "and the event, which is part of the listener's identity: "
            + logged);
    assertTrue(logged.contains("stops being portable"), () -> "what it costs the model: "
        + logged);
    assertTrue(
        logged.contains("What a listener method may write into the process instance depends"),
        () -> "and what only Camunda 8 has to say: which of its listeners writes into the process "
            + "instance and which of them does not: "
            + logged);
    assertTrue(
        logged.contains("TaskEvent.Event has no value"),
        () -> "the one ambiguity this design leaves, said out loud: "
            + logged);

  }

  @Test
  @DisplayName("A switch nobody needs is one line, not a frame")
  public void aSwitchNobodyNeedsIsOneLine(
      final CapturedOutput output) {

    final var logged = deploy(
        output,
        adapter(
            NameClashAvoidance.BY_ADAPTER,
            allowedBy("vanillabp.workflow-modules.loan-approval.adapters.c8.allow-listeners")),
        modelWithoutAListener());

    assertTrue(
        logged.contains("no model of it carries one"),
        () -> "the switch is on and nothing uses it, which is worth a sentence: "
            + logged);
    assertFalse(logged.contains("MODELLED LISTENERS ARE SERVED"), () -> "and no frame: "
        + logged);

  }

  @Test
  @DisplayName("A model with no listener and no property says nothing at all")
  public void aModelWithoutListenersSaysNothing(
      final CapturedOutput output) {

    final var logged = deploy(output, adapter(NameClashAvoidance.BY_ADAPTER, null), modelWithoutAListener());

    assertFalse(
        logged.contains("listener"),
        () -> "which is every application that never modelled one: "
            + logged);

  }

  @Test
  @DisplayName("Without the property the boot ends, naming the listeners, the levels and the cost")
  public void withoutThePropertyTheBootEnds(
      final CapturedOutput output) {

    final var service = adapter(NameClashAvoidance.BY_ADAPTER, null);
    final var model = modelWithAListener();

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> service.prepareBpmn(MODULE, null, FILE, PROCESS, model));

    final var message = refused.getMessage();
    assertTrue(
        message.contains("element 'Event_Done'"),
        () -> "the element a modeller has to find in their own model: "
            + message);
    assertTrue(
        message.contains("vanillabp.adapters.c8.allow-listeners") && message
            .contains("vanillabp.workflow-modules.loan-approval.adapters.c8.allow-listeners") && message.contains(
                "vanillabp.workflow-modules.loan-approval.workflows.LoanApproval.adapters.c8.allow-listeners"),
        () -> "all three levels the key is read at: "
            + message);
    assertTrue(
        message.contains("stops the workflow right there"),
        () -> "and what happens without the key, which is the reason the boot ends here: "
            + message);
    assertTrue(
        message.contains("io.vanillabp."),
        () -> "plus which listeners this is not about: "
            + message);

  }

  @Test
  @DisplayName("Two listeners of one element under one job type end the boot and both are named")
  public void twoListenersUnderOneJobTypeEndTheBoot() {

    final var service = adapter(
        NameClashAvoidance.BY_ADAPTER,
        allowedBy("vanillabp.adapters.c8.allow-listeners"));
    final var model = modelWithTwoListenersUnderOneJobType();

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> service.prepareBpmn(MODULE, null, FILE, PROCESS, model));

    final var message = refused.getMessage();
    assertTrue(
        message.contains("execution listener on 'start'") && message.contains("execution listener on 'end'"),
        () -> "both events, because version 1 picked one of them and said nothing: "
            + message);
    assertTrue(
        message.contains("job type of its own"),
        () -> "and the way out: "
            + message);

  }

  /**
   * The sentence both messages about such a listener carry.
   */
  private static final String NO_METHOD_NAMES_IT = "no @WorkflowTask method of this application names";

  /**
   * A core which serves the ordinary task of the model and no listener of it.
   */
  private static Camunda8DeploymentServiceTest.NoOpInvoker aCoreServingTheTaskOnly() {

    return new Camunda8DeploymentServiceTest.NoOpInvoker() {

      @Override
      public boolean workflowTaskHandlerExists(
          final String workflowModuleId,
          final String bpmnProcessId,
          final String taskDefinitionOrActivityId) {

        return "approve".equals(taskDefinitionOrActivityId);

      }

    };

  }

  /**
   * A core which serves the ordinary task of the model and no listener of it, and remembers
   * what it was handed for its wiring validation.
   */
  private static Camunda8DeploymentServiceTest.NoOpInvoker aCoreServingTheTaskOnlyRecording(
      final java.util.List<BpmnTaskSpec> specs) {

    return new Camunda8DeploymentServiceTest.NoOpInvoker() {

      @Override
      public boolean workflowTaskHandlerExists(
          final String workflowModuleId,
          final String bpmnProcessId,
          final String taskDefinitionOrActivityId) {

        return "approve".equals(taskDefinitionOrActivityId);

      }

      @Override
      public void validateTaskWiring(
          final String workflowModuleId,
          final String bpmnProcessId,
          final Collection<BpmnTaskSpec> tasks) {

        specs.addAll(tasks);

      }

    };

  }

  /**
   * The same core, answering that no {@code @WorkflowService} class of the application claims
   * the process.
   */
  private static Camunda8DeploymentServiceTest.NoOpInvoker aCoreWhoseProcessNobodyClaims() {

    return new Camunda8DeploymentServiceTest.NoOpInvoker() {

      @Override
      public boolean workflowTaskHandlerExists(
          final String workflowModuleId,
          final String bpmnProcessId,
          final String taskDefinitionOrActivityId) {

        return "approve".equals(taskDefinitionOrActivityId);

      }

      @Override
      public String resolveWorkflowAggregateIdName(
          final String workflowModuleId,
          final String bpmnProcessId) {
        throw new IllegalStateException(
            "no @WorkflowService class of this application claims '%s'".formatted(bpmnProcessId));
      }

    };

  }

  @Test
  @DisplayName("A job type no method names goes to the core's wiring validation, which ends the boot")
  public void aJobTypeNoMethodNamesGoesToTheCore(
      final CapturedOutput output) {

    final var specs = new ArrayList<BpmnTaskSpec>();
    final var logged = deploy(
        output,
        adapterServedBy(aCoreServingTheTaskOnlyRecording(specs), NameClashAvoidance.BY_ADAPTER, null),
        modelWithAListener());

    assertTrue(
        specs
            .stream()
            .anyMatch(spec -> spec.listener() && "archiveTheOrder".equals(spec.taskDefinition()) && "Event_Done"
                .equals(spec.activityId())),
        () -> "the listener is a task like any other: the core asks for a method or for "
            + "'implemented-externally', and ends the boot over neither: "
            + specs);
    assertFalse(
        logged.contains(NO_METHOD_NAMES_IT),
        () -> "the adapter says nothing of its own about it, the core's message is the one: "
            + logged);

  }

  @Test
  @DisplayName("An element template on a user task does not keep its listener from the core")
  public void aTemplateOnAUserTaskDoesNotCount(
      final CapturedOutput output) {

    final var specs = new ArrayList<BpmnTaskSpec>();
    final var logged = deploy(
        output,
        adapterServedBy(aCoreServingTheTaskOnlyRecording(specs), NameClashAvoidance.BY_ADAPTER, null),
        model("""
                <bpmn:serviceTask id="Activity_Approve">
                  <bpmn:extensionElements>
                    <zeebe:taskDefinition type="approve" />
                  </bpmn:extensionElements>
                </bpmn:serviceTask>
                <bpmn:userTask id="Activity_Review" zeebe:modelerTemplate="com.example.review-form:1">
                  <bpmn:extensionElements>
                    <zeebe:userTask />
                    <zeebe:formDefinition externalReference="approve" />
                    <zeebe:taskListeners>
                      <zeebe:taskListener eventType="creating" type="assignTheReviewer" />
                    </zeebe:taskListeners>
                  </bpmn:extensionElements>
                </bpmn:userTask>
            """));

    assertTrue(
        specs.stream().anyMatch(spec -> spec.listener() && "assignTheReviewer".equals(spec.taskDefinition())),
        () -> "a template on a user task presets a form or an assignee and says nothing about who "
            + "answers a job: "
            + specs);
    assertFalse(
        logged.contains("element built from an element template"),
        () -> "so it is not the WARN of an element of another runtime: "
            + logged);

  }

  @Test
  @DisplayName("The same listener in a process nobody claims keeps its warning, and the boot goes on")
  public void aJobTypeNoMethodNamesOnlyWarnsForAnUnclaimedProcess(
      final CapturedOutput output) {

    final var logged = deploy(
        output,
        adapterServedBy(aCoreWhoseProcessNobodyClaims(), NameClashAvoidance.BY_ADAPTER, null),
        modelWithAListener());

    assertTrue(
        logged.contains(NO_METHOD_NAMES_IT) && logged.contains("job type 'archiveTheOrder'"),
        () -> "a reader still has to learn that the job type is there: "
            + logged);
    assertTrue(
        logged.contains("No @WorkflowService class of this application claims this process"),
        () -> "and why this one is a warning: "
            + logged);
    assertFalse(
        logged.contains("take the listener out of the model"),
        () -> "nothing asks the reader to change a model which is none of ours: "
            + logged);
    assertFalse(
        logged.contains("MODELLED LISTENERS ARE SERVED"),
        () -> "and nothing of it is served: "
            + logged);

  }

  @Test
  @DisplayName("A listener on an element built from an element template is warned about, not refused")
  public void aListenerOnAnElementOfAnotherRuntimeIsOnlyWarnedAbout(
      final CapturedOutput output) {

    final var logged = deploy(
        output,
        adapterServedBy(aCoreServingTheTaskOnly(), NameClashAvoidance.BY_ADAPTER, null),
        model("""
                <bpmn:serviceTask id="Activity_Approve">
                  <bpmn:extensionElements>
                    <zeebe:taskDefinition type="approve" />
                  </bpmn:extensionElements>
                </bpmn:serviceTask>
                <bpmn:serviceTask id="Activity_Fetch" zeebe:modelerTemplate="io.camunda.connectors.HttpJson.v2">
                  <bpmn:extensionElements>
                    <zeebe:taskDefinition type="io.camunda:http-json:1" />
                    <zeebe:executionListeners>
                      <zeebe:executionListener eventType="end" type="auditTheCall" />
                    </zeebe:executionListeners>
                  </bpmn:extensionElements>
                </bpmn:serviceTask>
            """));

    assertTrue(
        logged.contains(NO_METHOD_NAMES_IT) && logged.contains("job type 'auditTheCall'"),
        () -> "the job type is named, because one a reader does not recognise is worth a look: "
            + logged);
    assertTrue(
        logged.contains("element built from an element template"),
        () -> "and why the boot goes on although the application claims this process: "
            + logged);

  }

  @Test
  @DisplayName("A start listener on a start event is refused whatever the key says")
  public void aStartListenerOnAStartEventIsRefused() {

    final var model = model("""
            <bpmn:startEvent id="Event_Begin">
              <bpmn:extensionElements>
                <zeebe:executionListeners>
                  <zeebe:executionListener eventType="start" type="tooEarly" />
                </zeebe:executionListeners>
              </bpmn:extensionElements>
            </bpmn:startEvent>
        """);
    final var service = adapter(
        NameClashAvoidance.BY_ADAPTER,
        allowedBy("vanillabp.adapters.c8.allow-listeners"));

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> service.prepareBpmn(MODULE, null, FILE, PROCESS, model));

    assertTrue(
        refused.getMessage().contains("allows no start listener there"),
        () -> "the cluster refuses the whole file over it, which is worth saying here rather than "
            + "leaving the cluster to say it about a rule: "
            + refused.getMessage());
    assertTrue(
        refused.getMessage().contains("'end'"),
        () -> "and the event type which works: "
            + refused.getMessage());

  }

  @Test
  @DisplayName("A served listener reaches the core's wiring validation as a task of its own")
  public void theListenerIsAmongTheSpecs(
      final CapturedOutput output) {

    final var specs = new ArrayList<String>();
    final var core = new Camunda8DeploymentServiceTest.NoOpInvoker() {

      @Override
      public void validateTaskWiring(
          final String workflowModuleId,
          final String bpmnProcessId,
          final Collection<BpmnTaskSpec> tasks) {

        tasks
            .stream()
            .map(BpmnTaskSpec::taskDefinition)
            .forEach(specs::add);

      }

    };

    deploy(
        output,
        adapterServedBy(core, NameClashAvoidance.BY_ADAPTER, allowedBy("vanillabp.adapters.c8.allow-listeners")),
        modelWithAListener());

    assertTrue(specs.contains("approve"), () -> "the ordinary task: "
        + specs);
    assertTrue(
        specs.contains("archiveTheOrder"),
        () -> "and the listener, which is what gets the core's two directions for free: a listener "
            + "nothing serves ends the boot, and a method serving no listener is reported: "
            + specs);

  }

  @Test
  @DisplayName("A method declaring @TaskId cannot serve a listener")
  public void aMethodWithATaskIdCannotServeAListener() {

    final var core = new Camunda8DeploymentServiceTest.NoOpInvoker() {

      @Override
      public boolean workflowTaskCompletesAsynchronously(
          final String workflowModuleId,
          final String bpmnProcessId,
          final String taskDefinitionOrActivityId) {

        return "archiveTheOrder".equals(taskDefinitionOrActivityId);

      }

    };
    final var service = adapterServedBy(
        core,
        NameClashAvoidance.BY_ADAPTER,
        allowedBy("vanillabp.adapters.c8.allow-listeners"));
    final var model = modelWithAListener();
    final var context = service.prepareBpmn(MODULE, null, FILE, PROCESS, model);

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> service.wireBpmn(MODULE, FILE, PROCESS, model, context));

    assertTrue(
        refused.getMessage().contains("@TaskId"),
        () -> "a listener job is completed when the method returns, so the id would complete nothing: "
            + refused.getMessage());

  }

  private static Camunda8AllowListenersResolver allowedBy(
      final String propertyKey) {

    return (
        workflowModuleId,
        bpmnProcessId) -> new Camunda8AllowListenersResolver.Setting(true, propertyKey);

  }

  private static Camunda8DeploymentService adapter(
      final NameClashAvoidance mode,
      final Camunda8AllowListenersResolver allowListenersResolver) {

    return adapterServedBy(new Camunda8DeploymentServiceTest.NoOpInvoker(), mode, allowListenersResolver);

  }

  /**
   * An adapter against an address nothing listens on, with the given core, the given scoping
   * mode and the given answer about listeners.
   */
  private static Camunda8DeploymentService adapterServedBy(
      final Camunda8DeploymentServiceTest.NoOpInvoker core,
      final NameClashAvoidance mode,
      final Camunda8AllowListenersResolver allowListenersResolver) {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:65535");
    final var scoping = TestScoping.of(mode);
    final var service = DeploymentServiceUnderTest.of(
        "c8", new Camunda8ClientFactory("c8", configuration), TestCollaborators
            .of(core, scoping),
        (
            workflowModuleId,
            bpmnProcessId,
            taskDefinition) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
        Duration
            .ofHours(1),
        adapterId -> configuration, scoping);
    service.setAllowListenersResolver(allowListenersResolver);
    return service;

  }

}
