package io.vanillabp.camunda8.deployment;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Collection;
import java.util.List;

import org.mockito.ArgumentMatchers;

import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.Process;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListeners;
import io.vanillabp.camunda8.Camunda8ProcessingContext;
import io.vanillabp.camunda8.TestCollaborators;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker;

/**
 * One BPMN file through the deployment pipeline, for the per-line tests which ask what the
 * process element of the deployed model carries afterwards.
 * <p>
 * Shared by the three per-line tests so that they differ in their assertions and in nothing
 * else. A model which differed per line would make them incomparable, which is the one thing
 * a per-line trio has to avoid.
 */
public final class Camunda8CancelDeploymentFixture {

  private Camunda8CancelDeploymentFixture() {
  }

  /**
   * The workflow module every model of this fixture belongs to.
   */
  public static final String MODULE = "test-module";

  /**
   * A process with one service task the application serves.
   */
  public static final String A_SERVED_PROCESS = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="Loans" isExecutable="true">
          <bpmn:startEvent id="LoanStart" />
          <bpmn:serviceTask id="ApproveLoan">
            <bpmn:extensionElements>
              <zeebe:taskDefinition type="approveLoan" />
            </bpmn:extensionElements>
          </bpmn:serviceTask>
          <bpmn:endEvent id="LoanEnd" />
        </bpmn:process>
      </bpmn:definitions>
      """;

  /**
   * What the pipeline left behind.
   *
   * @param model The model as it would be deployed
   * @param context What the pipeline carries on to <code>startWorkflowProcessing</code>
   */
  public record Wired(
                      BpmnModelInstance model,
                      Camunda8ProcessingContext context) {

    /**
     * What the listeners of the named process say, in the order the model carries them.
     *
     * @param bpmnProcessId The SCOPED process id
     * @return One "&lt;event&gt;:&lt;job type&gt;" per listener, empty where there are none
     */
    public List<String> whatTheProcessCarries(
        final String bpmnProcessId) {

      final var process = model
          .getModelElementsByType(Process.class)
          .stream()
          .filter(candidate -> bpmnProcessId.equals(candidate.getId()))
          .findFirst()
          .orElseThrow();
      final var listeners = process.getSingleExtensionElement(ZeebeExecutionListeners.class);
      if (listeners == null) {
        return List.of();
      }
      return listeners
          .getExecutionListeners()
          .stream()
          .map(listener -> "%s:%s".formatted(
              listener.getEventType() == null
                  ? "?"
                  : listener.getEventType().name(),
              listener.getType()))
          .toList();

    }

  }

  /**
   * Runs the pipeline over one file.
   *
   * @param xml The BPMN file
   * @param theApplicationWantsTheEnd Whether a <code>&#64;WorkflowEnded</code> method exists
   * @param theApplicationServesTheTask Whether a <code>&#64;WorkflowTask</code> method serves
   *          the service task of the model
   * @return What the pipeline left behind
   */
  public static Wired wire(
      final String xml,
      final boolean theApplicationWantsTheEnd,
      final boolean theApplicationServesTheTask) {

    final var deploymentService = deploymentService(theApplicationWantsTheEnd, theApplicationServesTheTask);
    final var models = deploymentService
        .readBpmn(MODULE, "loans.bpmn", new ByteArrayInputStream(xml.getBytes(UTF_8)), true);
    Camunda8ProcessingContext context = null;
    for (final var model : models) {
      context = deploymentService.prepareBpmn(MODULE, context, "loans.bpmn", model.getKey(), model.getValue());
      deploymentService.wireBpmn(MODULE, "loans.bpmn", model.getKey(), model.getValue(), context);
    }
    return new Wired(models.getFirst().getValue(), context);

  }

  /**
   * Says what the boot says about the cancelations this line cannot report.
   *
   * @param wired What the pipeline left behind
   * @return The BPMN processes the boot would name, empty where it says nothing
   */
  public static List<String> whatTheBootWouldSay(
      final Wired wired) {

    return List.copyOf(wired.context().getProcessesWithoutACancelationReport());

  }

  private static Camunda8DeploymentService deploymentService(
      final boolean theApplicationWantsTheEnd,
      final boolean theApplicationServesTheTask) {

    final var invoker = new Camunda8DeploymentServiceTest.NoOpInvoker() {

      @Override
      public String resolveWorkflowAggregateIdName(
          final String workflowModuleId,
          final String bpmnProcessId) {

        return "loanId";

      }

      @Override
      public boolean workflowTaskHandlerExists(
          final String workflowModuleId,
          final String bpmnProcessId,
          final String taskDefinition) {

        return theApplicationServesTheTask;

      }

      @Override
      public Collection<String> taskParameterNames(
          final String workflowModuleId,
          final String bpmnProcessId,
          final String taskDefinitionOrActivityId) {

        return List.of();

      }

    };
    final var workflowEndedInvoker = mock(WorkflowEndedInvoker.class);
    when(
        workflowEndedInvoker
            .workflowEndedHandlerExists(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
        .thenReturn(theApplicationWantsTheEnd);
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

}
