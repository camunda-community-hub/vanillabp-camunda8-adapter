package io.vanillabp.camunda8.wiring;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.search.enums.JobKind;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.Process;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListener;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListeners;

/**
 * What the per-line tests of {@link Camunda8CancelListeners} share: one model with a process
 * element, and the listeners of that element after the adapter wrote into it.
 * <p>
 * It lives in the shared test tree, because the three per-line tests would otherwise be three
 * copies of the same model - and a model which differs per line would make the tests
 * incomparable, which is the one thing a per-line pair has to avoid.
 */
public final class Camunda8CancelListenersFixture {

  private Camunda8CancelListenersFixture() {
  }

  /**
   * The process the tests write into, as the cluster would know it.
   */
  public static final String PROCESS = "test-module-CancelableProcess";

  /**
   * The job type the end listener of that process carries, which is the job type of its
   * cancelation as well.
   */
  public static final String JOB_TYPE = Camunda8TaskWiring.workflowEndedJobTypeOf(PROCESS);

  /**
   * A model with one process and nothing else in it.
   */
  public static BpmnModelInstance aProcess() {

    return Bpmn
        .createExecutableProcess(PROCESS)
        .startEvent()
        .endEvent()
        .done();

  }

  /**
   * The execution listeners of the process element of that model, or <code>null</code> where
   * nothing wrote any.
   */
  public static ZeebeExecutionListeners listenersOf(
      final BpmnModelInstance model) {

    final var process = model
        .getModelElementsByType(Process.class)
        .stream()
        .filter(candidate -> PROCESS.equals(candidate.getId()))
        .findFirst()
        .orElseThrow();
    return process.getSingleExtensionElement(ZeebeExecutionListeners.class);

  }

  /**
   * What the listeners of the process element say, in the order the model carries them.
   *
   * @param model The model
   * @return One "&lt;event&gt;:&lt;job type&gt;" per listener
   */
  public static java.util.List<String> whatTheProcessCarries(
      final BpmnModelInstance model) {

    final var listeners = listenersOf(model);
    if (listeners == null) {
      return java.util.List.of();
    }
    return listeners
        .getExecutionListeners()
        .stream()
        .map(Camunda8CancelListenersFixture::describe)
        .toList();

  }

  private static String describe(
      final ZeebeExecutionListener listener) {

    return "%s:%s".formatted(
        listener.getEventType() == null
            ? "?"
            : listener.getEventType().name(),
        listener.getType());

  }

  /**
   * A job as a worker of this adapter receives it.
   *
   * @param kind What kind of job the cluster says it is
   * @param eventTypeName The listener event it reports, as the client's enum spells it, or
   *          <code>null</code> for a job which reports none
   * @return The job
   */
  public static ActivatedJob aJobReporting(
      final JobKind kind,
      final String eventTypeName) {

    final var job = org.mockito.Mockito.mock(ActivatedJob.class);
    org.mockito.Mockito.lenient().when(job.getKind()).thenReturn(kind);
    org.mockito.Mockito
        .lenient()
        .when(job.getListenerEventType())
        .thenReturn(
            eventTypeName == null
                ? null
                : io.camunda.client.api.search.enums.ListenerEventType.valueOf(eventTypeName));
    return job;

  }

}
