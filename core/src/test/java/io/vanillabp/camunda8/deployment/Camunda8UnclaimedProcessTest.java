package io.vanillabp.camunda8.deployment;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;

import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.Message;
import io.camunda.zeebe.model.bpmn.instance.Process;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListeners;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeSubscription;
import io.vanillabp.camunda8.Camunda8ProcessingContext;
import io.vanillabp.camunda8.TestCollaborators;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A BPMN file carrying a process no <code>@WorkflowService</code> class of this
 * application claims. Such a process is deployed, because a file travels to the cluster as
 * a whole, and the application marked it as somebody else's - otherwise the core would have
 * ended the start over it before this adapter saw it.
 * <p>
 * This adapter leaves such a process as it was modelled: no listener, no correlation key, no
 * multi-instance mapping is written into it, no worker serves its jobs, and no check ends the
 * boot because of it. A message catch element without a correlation key is one the cluster
 * rejects the file over, and that rejection is what the developer then reads: writing a
 * substitute would change a process this application does not serve. Only what belongs to the
 * FILE reaches the process, a message element shared with a claimed process being one.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8UnclaimedProcessTest {

  private static final String MODULE = "test-module";

  /**
   * Two executable processes, one of them claimed by a workflow service and one not. Both
   * wait for a message of their own, neither of them models a correlation key.
   */
  private static final String CLAIMED_AND_UNCLAIMED = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:message id="Msg_LoanApproved" name="LoanApproved" />
        <bpmn:message id="Msg_CardApproved" name="CardApproved" />
        <bpmn:process id="Loans" isExecutable="true">
          <bpmn:serviceTask id="ApproveLoan">
            <bpmn:extensionElements>
              <zeebe:taskDefinition type="approveLoan" />
            </bpmn:extensionElements>
          </bpmn:serviceTask>
          <bpmn:intermediateCatchEvent id="AwaitLoanApproval">
            <bpmn:messageEventDefinition id="LoanApprovedDef" messageRef="Msg_LoanApproved" />
          </bpmn:intermediateCatchEvent>
        </bpmn:process>
        <bpmn:process id="Cards" isExecutable="true">
          <bpmn:serviceTask id="ApproveCard">
            <bpmn:extensionElements>
              <zeebe:taskDefinition type="approveCard" />
            </bpmn:extensionElements>
          </bpmn:serviceTask>
          <bpmn:intermediateCatchEvent id="AwaitCardApproval">
            <bpmn:messageEventDefinition id="CardApprovedDef" messageRef="Msg_CardApproved" />
          </bpmn:intermediateCatchEvent>
        </bpmn:process>
      </bpmn:definitions>
      """;

  /**
   * An unclaimed process which carries no task at all, only the message it waits for -
   * the only shape which could reach the correlation key before the core started
   * collecting unclaimed processes instead of refusing them.
   */
  private static final String UNCLAIMED_WITHOUT_ANY_TASK = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:message id="Msg_CardApproved" name="CardApproved" />
        <bpmn:process id="Cards" isExecutable="true">
          <bpmn:startEvent id="CardStart" />
          <bpmn:intermediateCatchEvent id="AwaitCardApproval">
            <bpmn:messageEventDefinition id="CardApprovedDef" messageRef="Msg_CardApproved" />
          </bpmn:intermediateCatchEvent>
          <bpmn:endEvent id="CardEnd" />
        </bpmn:process>
      </bpmn:definitions>
      """;

  /**
   * ONE message element shared by two processes, the CLAIMED one written first. A message
   * belongs to the file rather than to a process, so the injection for the claimed process
   * would give the unclaimed one a subscription it never modelled - which is why the file
   * is read before any of it is wired.
   */
  private static final String A_SHARED_MESSAGE = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:message id="Msg_Shared" name="SharedApproval" />
        <bpmn:process id="Loans" isExecutable="true">
          <bpmn:intermediateCatchEvent id="AwaitOnLoans">
            <bpmn:messageEventDefinition id="LoansDef" messageRef="Msg_Shared" />
          </bpmn:intermediateCatchEvent>
        </bpmn:process>
        <bpmn:process id="Cards" isExecutable="true">
          <bpmn:intermediateCatchEvent id="AwaitOnCards">
            <bpmn:messageEventDefinition id="CardsDef" messageRef="Msg_Shared" />
          </bpmn:intermediateCatchEvent>
        </bpmn:process>
      </bpmn:definitions>
      """;

  /**
   * A claimed process waiting for a message it models no correlation key for, next to an
   * unclaimed process whose message carries the key its modeller wrote. Nothing is missing
   * for the cluster here, so the file deploys and the claimed process gets its aggregate.
   */
  private static final String A_COMPLETE_UNCLAIMED_PROCESS = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:message id="Msg_LoanApproved" name="LoanApproved" />
        <bpmn:message id="Msg_CardApproved" name="CardApproved">
          <bpmn:extensionElements>
            <zeebe:subscription correlationKey="=applicationNumber" />
          </bpmn:extensionElements>
        </bpmn:message>
        <bpmn:process id="Loans" isExecutable="true">
          <bpmn:intermediateCatchEvent id="AwaitLoanApproval">
            <bpmn:messageEventDefinition id="LoanApprovedDef" messageRef="Msg_LoanApproved" />
          </bpmn:intermediateCatchEvent>
        </bpmn:process>
        <bpmn:process id="Cards" isExecutable="true">
          <bpmn:intermediateCatchEvent id="AwaitCardApproval">
            <bpmn:messageEventDefinition id="CardApprovedDef" messageRef="Msg_CardApproved" />
          </bpmn:intermediateCatchEvent>
        </bpmn:process>
      </bpmn:definitions>
      """;

  /**
   * An unclaimed process the cluster is perfectly happy with: it waits for nothing and
   * therefore owes no correlation key. The shape the workflow-end listener is asked about.
   */
  private static final String UNCLAIMED_WITHOUT_A_MESSAGE = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="Cards" isExecutable="true">
          <bpmn:startEvent id="CardStart" />
          <bpmn:endEvent id="CardEnd" />
        </bpmn:process>
      </bpmn:definitions>
      """;

  /**
   * An unclaimed process with a Camunda-managed user task and a multi-instance service task:
   * the two elements this adapter writes into for a claimed process.
   */
  private static final String UNCLAIMED_WITH_A_USER_TASK_AND_AN_ITERATION = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="Cards" isExecutable="true">
          <bpmn:userTask id="ApproveCard">
            <bpmn:extensionElements>
              <zeebe:userTask />
              <zeebe:formDefinition externalReference="approveCard" />
            </bpmn:extensionElements>
          </bpmn:userTask>
          <bpmn:serviceTask id="NotifyCard">
            <bpmn:extensionElements>
              <zeebe:taskDefinition type="notifyCard" />
            </bpmn:extensionElements>
            <bpmn:multiInstanceLoopCharacteristics>
              <bpmn:extensionElements>
                <zeebe:loopCharacteristics inputCollection="=recipients" inputElement="recipient" />
              </bpmn:extensionElements>
            </bpmn:multiInstanceLoopCharacteristics>
          </bpmn:serviceTask>
        </bpmn:process>
      </bpmn:definitions>
      """;

  /**
   * A deployment service whose core answers the given aggregate-ID variable per BPMN
   * process and throws for every process the function answers <code>null</code> for -
   * which is what the core does for a process no workflow service claims.
   */
  private Camunda8DeploymentService deploymentService(
      final Function<String, String> aggregateIdNames) {

    return deploymentService(aggregateIdNames, mock(WorkflowEndedInvoker.class));

  }

  /**
   * The same, with the core's answer about whether the end of a workflow of a process has
   * to be reported at all.
   */
  private Camunda8DeploymentService deploymentService(
      final Function<String, String> aggregateIdNames,
      final WorkflowEndedInvoker workflowEndedInvoker) {

    final var invoker = new Camunda8DeploymentServiceTest.NoOpInvoker() {

      @Override
      public String resolveWorkflowAggregateIdName(
          final String workflowModuleId,
          final String bpmnProcessId) {

        final var name = aggregateIdNames.apply(bpmnProcessId);
        if (name == null) {
          // word for word what the core answers for a process nothing claims
          throw new IllegalStateException(
              ("No @WorkflowService class is registered for BPMN process '%s' of workflow module "
                  + "'%s' - the aggregate-ID variable name cannot be determined!")
                  .formatted(bpmnProcessId, workflowModuleId));
        }
        return name;

      }

      @Override
      public Collection<String> taskParameterNames(
          final String workflowModuleId,
          final String bpmnProcessId,
          final String taskDefinitionOrActivityId) {

        return List.of();

      }

    };
    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:65535");
    return DeploymentServiceUnderTest.of(
        "c8", new Camunda8ClientFactory("c8", configuration), TestCollaborators
            .of(invoker, workflowEndedInvoker),
        (
            m,
            p,
            t) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
        Duration
            .ofHours(1),
        adapterId -> configuration);

  }

  /**
   * The executable processes of the given file, each of them paired with the one model of
   * the file.
   */
  private List<Map.Entry<String, BpmnModelInstance>> executableProcessesOf(
      final Camunda8DeploymentService deploymentService,
      final String xml) {

    return deploymentService
        .readBpmn(MODULE, "cards.bpmn", new ByteArrayInputStream(xml.getBytes(UTF_8)), true);

  }

  /**
   * Runs the deployment pipeline the way the core runs it: every process of the file is
   * prepared and wired before the next one is prepared, which is the order a file-wide
   * verdict has to be independent of.
   */
  private Camunda8ProcessingContext runPipeline(
      final Camunda8DeploymentService deploymentService,
      final List<Map.Entry<String, BpmnModelInstance>> models) {

    Camunda8ProcessingContext context = null;
    for (final var model : models) {
      context = deploymentService.prepareBpmn(MODULE, context, "cards.bpmn", model.getKey(), model.getValue());
      deploymentService.wireBpmn(MODULE, "cards.bpmn", model.getKey(), model.getValue(), context);
    }
    return context;

  }

  /**
   * The whole pipeline for one file, from reading it to the model and the context it left
   * behind.
   */
  private Wired wire(
      final Camunda8DeploymentService deploymentService,
      final String xml) {

    final var models = executableProcessesOf(deploymentService, xml);
    return new Wired(models.getFirst().getValue(), runPipeline(deploymentService, models));

  }

  /**
   * @param model The model as the pipeline left it
   * @param context What the pipeline carries on to <code>startWorkflowProcessing</code>
   */
  private record Wired(
                       BpmnModelInstance model,
                       Camunda8ProcessingContext context) {
  }

  /**
   * The correlation-key expression of the named message, or <code>null</code> where the
   * message carries no <code>zeebe:subscription</code> at all.
   */
  private String correlationKeyOf(
      final BpmnModelInstance model,
      final String messageName) {

    final var subscription = messageNamed(model, messageName)
        .getSingleExtensionElement(ZeebeSubscription.class);
    return subscription == null
        ? null
        : subscription.getCorrelationKey();

  }

  /**
   * The message element of the given name, which is one per FILE rather than one per
   * process.
   */
  private Message messageNamed(
      final BpmnModelInstance model,
      final String messageName) {

    return model
        .getModelElementsByType(Message.class)
        .stream()
        .filter(candidate -> messageName.equals(candidate.getName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("the model carries no message '%s'".formatted(messageName)));

  }

  @Test
  @DisplayName("A process nobody serves which waits for a message is left without a correlation key")
  public void aProcessWaitingForAMessageIsLeftAlone() {

    final var wired = wire(
        deploymentService(bpmnProcessId -> "Loans".equals(bpmnProcessId)
            ? "loanId"
            : null),
        CLAIMED_AND_UNCLAIMED);

    assertEquals(
        "=loanId",
        correlationKeyOf(wired.model(), "LoanApproved"),
        "the process this application serves correlates by its workflow aggregate");
    assertNull(
        correlationKeyOf(wired.model(), "CardApproved"),
        "the process nobody serves keeps the model its owner wrote, and the cluster says what it "
            + "misses");
    assertTrue(
        wired
            .context()
            .getTasksToWire()
            .stream()
            .noneMatch(task -> "ApproveCard".equals(task.activityId())),
        "and no worker is opened for its job");

  }

  @Test
  @DisplayName("An unclaimed process holding no task at all is left alone the same way")
  public void anUnclaimedProcessWithoutAnyTaskIsLeftAlone() {

    final var wired = wire(deploymentService(bpmnProcessId -> null), UNCLAIMED_WITHOUT_ANY_TASK);

    assertNull(correlationKeyOf(wired.model(), "CardApproved"));

  }

  @Test
  @DisplayName("A message two processes share belongs to the file, and the claimed process writes its key")
  public void aSharedMessageGetsTheKeyOfTheClaimedProcess() {

    final var wired = wire(
        deploymentService(bpmnProcessId -> "Loans".equals(bpmnProcessId)
            ? "loanId"
            : null),
        A_SHARED_MESSAGE);

    assertEquals(
        "=loanId",
        correlationKeyOf(wired.model(), "SharedApproval"),
        "a message element is one per file, so what the claimed process needs reaches it");

  }

  @Test
  @DisplayName("A claimed process next to an unclaimed one still gets its real correlation key")
  public void aClaimedProcessNextToAnUnclaimedOneGetsItsRealKey() {

    final var wired = wire(
        deploymentService(bpmnProcessId -> "Loans".equals(bpmnProcessId)
            ? "loanId"
            : null),
        A_COMPLETE_UNCLAIMED_PROCESS);

    assertEquals(
        "=loanId",
        correlationKeyOf(wired.model(), "LoanApproved"),
        "the process this application serves correlates by its workflow aggregate, which is what "
            + "the injection is for");
    assertEquals(
        "=applicationNumber",
        correlationKeyOf(wired.model(), "CardApproved"),
        "and the key the modeller of the other process wrote is untouched - a process nothing "
            + "serves whose model is complete costs nothing");

  }

  @Test
  @DisplayName("An unclaimed process gets no workflow-end listener even where the end is wanted")
  public void anUnclaimedProcessGetsNoWorkflowEndListener() {

    // a workflow module releasing the records of its processed task deliveries when a
    // workflow ends wants the notification for EVERY process of the module, without any
    // application method saying so - which is how an unclaimed process gets here
    final var workflowEndedInvoker = mock(WorkflowEndedInvoker.class);
    when(workflowEndedInvoker.workflowEndedHandlerExists(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
        .thenReturn(true);
    final var deploymentService = deploymentService(bpmnProcessId -> null, workflowEndedInvoker);

    final var wired = wire(deploymentService, UNCLAIMED_WITHOUT_A_MESSAGE);

    final var process = wired.model()
        .getModelElementsByType(Process.class)
        .stream()
        .filter(candidate -> "Cards".equals(candidate.getId()))
        .findFirst()
        .orElseThrow();
    assertNull(
        process.getSingleExtensionElement(ZeebeExecutionListeners.class),
        "an 'end' listener nobody can activate would stop the workflow at its own end, and the "
            + "listener is this adapter's own addition rather than something the cluster wants");
    assertTrue(
        wired.context().getWorkflowEndedProcessesToWire().isEmpty(),
        "so the process is kept out of the list the workers are opened from");

    deploymentService.startWorkflowProcessing(MODULE, wired.context());
    try {
      assertTrue(
          wired.context().getOpenWorkers().isEmpty(),
          "and no workflow-end worker is opened for it");
    } finally {
      deploymentService.stopWorkflowProcessing(MODULE, wired.context());
    }

  }


  @Test
  @DisplayName("An unclaimed process gets no start listener either")
  public void anUnclaimedProcessGetsNoStartListener() {

    final var deploymentService = deploymentService(bpmnProcessId -> null);

    final var wired = wire(deploymentService, UNCLAIMED_WITHOUT_A_MESSAGE);

    final var startEvent = wired.model()
        .getModelElementsByType(io.camunda.zeebe.model.bpmn.instance.StartEvent.class)
        .stream()
        .filter(candidate -> "CardStart".equals(candidate.getId()))
        .findFirst()
        .orElseThrow();
    assertNull(
        startEvent.getSingleExtensionElement(ZeebeExecutionListeners.class),
        "the listener holds the instance until its job is answered, and the core has no workflow "
            + "service to answer for a process nothing claims");
    assertTrue(
        wired.context().getBpmsInitiatedStartsToWire().isEmpty(),
        "so the start event is kept out of the list the workers are opened from");

  }

  @Test
  @DisplayName("An unclaimed process gets no user task listener and no multi-instance mapping")
  public void anUnclaimedProcessKeepsItsElementsAsModelled() {

    final var deploymentService = deploymentService(bpmnProcessId -> null);
    final var models = executableProcessesOf(deploymentService, UNCLAIMED_WITH_A_USER_TASK_AND_AN_ITERATION);
    final var before = io.camunda.zeebe.model.bpmn.Bpmn.convertToString(models.getFirst().getValue());

    final var wired = new Wired(models.getFirst().getValue(), runPipeline(deploymentService, models));

    assertEquals(
        before,
        io.camunda.zeebe.model.bpmn.Bpmn.convertToString(wired.model()),
        "nothing is written into a process nobody claims");
    assertTrue(wired.context().getUserTasksToWire().isEmpty(), "no worker for its user task");
    assertTrue(wired.context().getTasksToWire().isEmpty(), "and none for its service task");

  }

}
