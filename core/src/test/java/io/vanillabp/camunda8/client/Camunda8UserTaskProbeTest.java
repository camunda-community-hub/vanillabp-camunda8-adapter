package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.response.UserTaskProperties;
import io.camunda.client.api.search.enums.JobKind;
import io.camunda.client.api.search.enums.ListenerEventType;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The mark a probe of this adapter leaves on the listener job it fires.
 * <p>
 * The empty update fires a modelled <code>updating</code> listener although it changes
 * nothing, so the job has to be recognisable. Both halves of the mark are asserted, because
 * either half on its own belongs to somebody else too: the action is a string anybody may
 * send, and an empty change list is not this adapter's doing either.
 * <p>
 * What the cluster answers such a probe with is read by
 * {@code Camunda8Errors#refusedAboutAUserTaskItHolds} and asserted where its siblings are,
 * in {@code Camunda8ErrorsTest}.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8UserTaskProbeTest {

  @Test
  @DisplayName("Both halves of the mark, and nothing less")
  public void bothHalvesOfTheMark() {

    assertTrue(
        Camunda8UserTaskProbe
            .isOurOwnProbe(
                aListenerJob(
                    JobKind.TASK_LISTENER,
                    ListenerEventType.UPDATING,
                    Camunda8UserTaskProbe.ACTION,
                    List.of())));

    assertFalse(
        Camunda8UserTaskProbe
            .isOurOwnProbe(
                aListenerJob(
                    JobKind.TASK_LISTENER,
                    ListenerEventType.UPDATING,
                    "somebody-elses-action",
                    List.of())),
        "an empty change list alone is not ours");
    assertFalse(
        Camunda8UserTaskProbe
            .isOurOwnProbe(
                aListenerJob(
                    JobKind.TASK_LISTENER,
                    ListenerEventType.UPDATING,
                    Camunda8UserTaskProbe.ACTION,
                    List.of("dueDate"))),
        "and the action alone is a string anybody may send");

  }

  @Test
  @DisplayName("Only an 'updating' task listener can be a probe of ours")
  public void onlyAnUpdatingTaskListener() {

    assertFalse(
        Camunda8UserTaskProbe
            .isOurOwnProbe(
                aListenerJob(
                    JobKind.TASK_LISTENER,
                    ListenerEventType.COMPLETING,
                    Camunda8UserTaskProbe.ACTION,
                    List.of())));
    assertFalse(
        Camunda8UserTaskProbe
            .isOurOwnProbe(
                aListenerJob(
                    JobKind.EXECUTION_LISTENER,
                    ListenerEventType.UPDATING,
                    Camunda8UserTaskProbe.ACTION,
                    List.of())));

    final var withoutAUserTask = mock(ActivatedJob.class);
    when(withoutAUserTask.getKind()).thenReturn(JobKind.TASK_LISTENER);
    when(withoutAUserTask.getListenerEventType()).thenReturn(ListenerEventType.UPDATING);
    when(withoutAUserTask.getUserTask()).thenReturn(null);
    assertFalse(Camunda8UserTaskProbe.isOurOwnProbe(withoutAUserTask));

  }

  private static ActivatedJob aListenerJob(
      final JobKind kind,
      final ListenerEventType event,
      final String action,
      final List<String> changedAttributes) {

    final var userTask = mock(UserTaskProperties.class);
    when(userTask.getAction()).thenReturn(action);
    when(userTask.getChangedAttributes()).thenReturn(changedAttributes);
    final var job = mock(ActivatedJob.class);
    when(job.getKind()).thenReturn(kind);
    when(job.getListenerEventType()).thenReturn(event);
    when(job.getUserTask()).thenReturn(userTask);
    return job;

  }

}
