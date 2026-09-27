package io.vanillabp.camunda8.deployment;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * a whole, and the core reports it instead of validating it - so everything the adapter
 * wires has to cope with a process it can learn no workflow aggregate for.
 * <p>
 * Two things in the deployment need that aggregate's ID variable, and the answer differs
 * per thing. The execution listener reporting the end of a workflow is this adapter's own
 * addition, so it is simply left out where nobody could answer its job. The correlation
 * key of a message subscription is not: Camunda 8 accepts no message catch element whose
 * message carries none, and it rejects the whole file over it, so a process waiting for a
 * message is incomplete for the cluster whatever VanillaBP does. Writing a substitute
 * there would change a process this application does not serve and hide the gap from the
 * modeller, so the deployment ends the boot instead and says what the model is missing.
 * <p>
 * That verdict is reached before the file is rewritten, which is what makes it independent
 * of the order the processes of a file are wired in: a message element belongs to the file
 * rather than to one process, so an injection for a claimed process could otherwise fill
 * the gap of the unclaimed one standing next to it.
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
  @DisplayName("A process nobody serves which waits for a message ends the deployment")
  public void aProcessWaitingForAMessageItCannotCorrelateEndsTheDeployment() {

    final var deploymentService = deploymentService(bpmnProcessId -> "Loans".equals(bpmnProcessId)
        ? "loanId"
        : null);

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> wire(deploymentService, CLAIMED_AND_UNCLAIMED),
        "a model the cluster would reject has to be reported while starting");

    final var reported = refused.getMessage();
    assertTrue(reported.contains("cards.bpmn"), () -> "the file the cluster would reject: "
        + reported);
    assertTrue(reported.contains(MODULE), () -> "the workflow module it belongs to: "
        + reported);
    assertTrue(reported.contains("'Cards'"), () -> "the process which is incomplete: "
        + reported);
    assertTrue(reported.contains("AwaitCardApproval"), () -> "the element which waits: "
        + reported);
    assertTrue(reported.contains("CardApproved"), () -> "the message it waits for: "
        + reported);
    assertTrue(reported.contains("every executable process"), () -> "of whom the cluster demands it: "
        + reported);
    assertTrue(
        reported.contains("Camunda 8 demands a 'zeebe:subscription'"),
        () -> "and whose demand that is, which is not VanillaBP's: "
            + reported);
    assertTrue(
        reported.contains("reject the file as a whole"),
        () -> "the reason the boot ends here rather than at the cluster: "
            + reported);
    assertTrue(
        reported.contains("isExecutable=\"false\"") && reported.contains("model the correlation key"),
        () -> "and both ways out: "
            + reported);
    assertTrue(
        reported.contains("'Loans'") || !reported.contains("LoanApproved"),
        () -> "the message of the claimed process is not what this is about: "
            + reported);

  }

  @Test
  @DisplayName("The file is refused before anything of it was rewritten")
  public void theFileIsRefusedBeforeAnythingOfItWasRewritten() {

    final var deploymentService = deploymentService(bpmnProcessId -> "Loans".equals(bpmnProcessId)
        ? "loanId"
        : null);
    final var models = executableProcessesOf(deploymentService, CLAIMED_AND_UNCLAIMED);
    final var model = models.getFirst().getValue();

    assertThrows(
        IllegalStateException.class,
        () -> runPipeline(deploymentService, models));

    assertNull(
        correlationKeyOf(model, "LoanApproved"),
        "the process this application does serve was not wired either - the developer reads the "
            + "verdict about the file before this adapter changed a single element of it");

  }

  @Test
  @DisplayName("An unclaimed process holding no task at all is judged the same way")
  public void anUnclaimedProcessWithoutAnyTaskIsJudgedTheSameWay() {

    final var deploymentService = deploymentService(bpmnProcessId -> null);

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> wire(deploymentService, UNCLAIMED_WITHOUT_ANY_TASK),
        "a process without tasks was the older half of the same shape");

    assertTrue(
        refused.getMessage().contains("AwaitCardApproval"),
        () -> "and it names the element which waits as well: "
            + refused.getMessage());

  }

  @Test
  @DisplayName("A message two processes share is judged by the model, not by the wiring order")
  public void aSharedMessageIsJudgedByTheModel() {

    final var deploymentService = deploymentService(bpmnProcessId -> "Loans".equals(bpmnProcessId)
        ? "loanId"
        : null);

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> wire(deploymentService, A_SHARED_MESSAGE),
        "the claimed process is wired first here, so its injection would have given the unclaimed "
            + "process a subscription its modeller never wrote");

    assertTrue(
        refused.getMessage().contains("AwaitOnCards"),
        () -> "the element of the process nothing serves is what is missing a key: "
            + refused.getMessage());

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

}
