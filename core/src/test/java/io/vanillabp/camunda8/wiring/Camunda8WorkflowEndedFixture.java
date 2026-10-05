package io.vanillabp.camunda8.wiring;

import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.mockito.Mockito;

import io.camunda.client.api.CamundaFuture;
import io.camunda.client.api.command.CompleteJobCommandStep1;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.response.CompleteJobResponse;
import io.camunda.client.api.search.enums.JobKind;
import io.camunda.client.api.search.enums.ListenerEventType;
import io.camunda.client.api.worker.JobClient;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedContext;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker;

/**
 * One execution-listener job of a process, handed to the handler which answers it.
 * <p>
 * Shared by the test in this tree and by the per-line test of the 8.10 directory, which is the
 * only line whose cluster produces a <code>cancel</code> job at all. Two harnesses for one
 * handler would let the two drift apart, and the whole point of the per-line pair is that they
 * ask the same question of different lines.
 */
public final class Camunda8WorkflowEndedFixture {

  private Camunda8WorkflowEndedFixture() {
  }

  /**
   * The instance key every job of this fixture belongs to.
   */
  public static final String INSTANCE_KEY = "2251799813685249";

  /**
   * The key of the job itself.
   */
  public static final long JOB_KEY = 4711L;

  /**
   * What the handler did with one job.
   *
   * @param reported What it told the application, empty where it told it nothing
   * @param jobWasCompleted Whether it answered the cluster, which it always has to: the
   *          listener job gates a transition the cluster is inside
   */
  public record WhatHappened(
                             List<WorkflowEndedContext> reported,
                             boolean jobWasCompleted) {

  }

  /**
   * Runs the handler over one listener job.
   *
   * @param eventType The event the job reports, or <code>null</code> for a job reporting none
   * @param variables What the job carries
   * @return What the handler did
   */
  public static WhatHappened whatTheHandlerDoesWith(
      final ListenerEventType eventType,
      final Map<String, Object> variables) {

    final var reported = new ArrayList<WorkflowEndedContext>();
    final var invoker = new WorkflowEndedInvoker() {

      @Override
      public void workflowEnded(
          final String workflowModuleId,
          final String bpmnProcessId,
          final WorkflowEndedContext context) {
        reported.add(context);
      }

      @Override
      public boolean workflowEndedHandlerExists(
          final String workflowModuleId,
          final String bpmnProcessId) {
        return true;
      }

    };

    final var client = mock(JobClient.class);
    final var completion = mock(CompleteJobCommandStep1.class, RETURNS_SELF);
    @SuppressWarnings("unchecked")
    final CamundaFuture<CompleteJobResponse> answer = mock(CamundaFuture.class);
    Mockito.lenient().when(answer.join()).thenReturn(null);
    Mockito.lenient().when(completion.send()).thenReturn(answer);
    Mockito.lenient().when(client.newCompleteCommand(Mockito.anyLong())).thenReturn(completion);

    new Camunda8WorkflowEndedHandler("c8", "test-module", "TestProcess", "id", invoker, null, null)
        .handle(client, aJobReporting(eventType, variables));

    return new WhatHappened(
        List.copyOf(reported), Mockito.mockingDetails(client).getInvocations().stream().anyMatch(
            invocation -> "newCompleteCommand".equals(invocation.getMethod().getName())));

  }

  /**
   * Runs the handler over a listener job carrying the aggregate-ID variable.
   *
   * @param eventType The event the job reports
   * @return What the handler did
   */
  public static WhatHappened whatTheHandlerDoesWith(
      final ListenerEventType eventType) {

    return whatTheHandlerDoesWith(eventType, Map.of("id", "agg-1"));

  }

  private static ActivatedJob aJobReporting(
      final ListenerEventType eventType,
      final Map<String, Object> variables) {

    final var job = mock(ActivatedJob.class);
    Mockito.lenient().when(job.getKey()).thenReturn(JOB_KEY);
    Mockito.lenient().when(job.getType()).thenReturn("io.vanillabp.workflowEnd:TestProcess");
    Mockito.lenient().when(job.getKind()).thenReturn(JobKind.EXECUTION_LISTENER);
    Mockito.lenient().when(job.getListenerEventType()).thenReturn(eventType);
    Mockito.lenient().when(job.getProcessInstanceKey()).thenReturn(Long.valueOf(INSTANCE_KEY));
    Mockito.lenient().when(job.getProcessDefinitionVersion()).thenReturn(1);
    Mockito.lenient().when(job.getVariablesAsMap()).thenReturn(variables);
    return job;

  }

}
