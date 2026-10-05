package io.vanillabp.camunda8.processservice;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.CamundaFuture;
import io.camunda.client.api.ProblemDetail;
import io.camunda.client.api.command.ClientHttpException;
import io.camunda.client.api.command.CompleteUserTaskCommandStep1;
import io.camunda.client.api.command.ProblemException;
import io.camunda.client.api.command.UpdateUserTaskCommandStep1;
import io.camunda.client.api.response.CompleteUserTaskResponse;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.client.Camunda8CommandRetry;
import io.vanillabp.integration.adapter.spi.PreCommitRegistrar;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.TaskNotFoundException;

/**
 * Completing a user task the cluster has not finished creating.
 * <p>
 * VanillaBP tells an application about a Camunda-managed user task from the
 * <code>creating</code> listener of that task, and the task stands in state
 * <code>CREATING</code> until that listener job is answered. An application which completes
 * the task in the same breath therefore addresses a task which is still being created, and
 * the cluster answers HTTP <code>409</code> with the title <code>INVALID_STATE</code> to
 * every command against it - the empty update of phase one and the completion of phase two
 * alike.
 * <p>
 * Both phases have to read that answer as "the task is there". Phase one aborts the caller's
 * transaction for a task which is GONE, and a task the cluster refuses a command ABOUT is not
 * gone. Phase two waits the state out instead of leaving the entry to the outbox, which would
 * park the completion for <code>attempt-frequency</code>.
 * <p>
 * The state is forced against a real cluster by
 * {@code Camunda8UserTaskStillCreatingIT}, with a model whose second
 * <code>creating</code> listener nobody answers.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8UserTaskStillCreatingTest {

  private static final WorkflowScope SCOPE = WorkflowScope.of("test-module", "TestProcess");

  private static final String TASK_ID = "2251799813685249";

  private final CamundaClient client = mock(CamundaClient.class);

  private final List<Runnable> preCommitChecks = new ArrayList<>();

  /**
   * What the cluster answers about a user task it is still creating, measured against
   * 8.9.16 and 8.9.19 for the update as well as for the completion.
   */
  private static ProblemException stillCreating(
      final String command) {

    final var details = new ProblemDetail();
    details.setStatus(409);
    details.setTitle("INVALID_STATE");
    return new ProblemException(
        409, "Expected to %s user task with key '%s', but it is in state 'CREATING'"
            .formatted(command, TASK_ID), details);

  }

  @Test
  @DisplayName("The pre-commit check lets a task the cluster is still creating through")
  public void thePreCommitCheckLetsATaskWhichIsStillBeingCreatedThrough() {

    theClusterAnswersTheUpdateWith(stillCreating("update"));

    registerThePreCommitCheckOfACompletion();

    assertDoesNotThrow(
        () -> preCommitChecks.getFirst().run(),
        "a task the cluster refuses a command about is a task the cluster has, so the caller's "
            + "transaction commits and phase two sends the completion");

  }

  @Test
  @DisplayName("The pre-commit check still aborts the transaction of a task which is gone")
  public void thePreCommitCheckStillStopsATaskWhichIsGone() {

    theClusterAnswersTheUpdateWith(new ClientHttpException("Failed with code 404", 404, "user task not found"));

    registerThePreCommitCheckOfACompletion();

    assertThrows(
        TaskNotFoundException.class,
        () -> preCommitChecks.getFirst().run(),
        "the answer of a task which is really gone is untouched by this");

  }

  @Test
  @DisplayName("A task the cluster is still creating is a task the BPMS holds")
  public void aTaskWhichIsStillBeingCreatedIsActive() {

    theClusterAnswersTheUpdateWith(stillCreating("update"));

    assertEquals(
        WorkflowAwareness.ACTIVE,
        aService().awarenessOfUserTask(SCOPE, "agg-1", TASK_ID),
        "the cluster just said it holds that task, so the election hears ACTIVE rather than an "
            + "outage it would wait out");

  }

  @Test
  @DisplayName("The completion waits out a task the cluster is still creating")
  public void theCompletionWaitsOutATaskWhichIsStillBeingCreated() {

    final var completion = theClusterAnswersTheCompletionWith(
        stillCreating("complete"),
        stillCreating("complete"));

    assertDoesNotThrow(
        this::completeTheUserTask,
        "the state passes within a round trip, so the completion goes through without the outbox "
            + "waiting attempt-frequency for it");

    Mockito.verify(completion, Mockito.times(3)).send();

  }

  @Test
  @DisplayName("A task which stays in that state is left to the outbox after the last attempt")
  public void aTaskWhichNeverLeavesThatStateIsLeftToTheOutbox() {

    final var completion = theClusterAnswersTheCompletionWith(
        stillCreating("complete"),
        stillCreating("complete"),
        stillCreating("complete"),
        stillCreating("complete"),
        stillCreating("complete"));

    assertThrows(
        ProblemException.class,
        this::completeTheUserTask,
        "the attempts are bounded, and what comes after them is the outbox doing what it does "
            + "today");

    Mockito
        .verify(completion, Mockito.times(Camunda8CommandRetry.MAX_ATTEMPTS))
        .send();

  }

  private void registerThePreCommitCheckOfACompletion() {

    PhaseOperations
        .phaseOne(
            aService(),
            PhaseOperation.COMPLETE_USER_TASK,
            "test-module",
            "TestProcess",
            null,
            new Object(),
            PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID, TASK_ID));

  }

  private void completeTheUserTask() {

    PhaseOperations
        .phaseTwo(
            aService(),
            PhaseOperation.COMPLETE_USER_TASK,
            "test-module",
            "TestProcess",
            null,
            "agg-1",
            PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID, TASK_ID));

  }

  /**
   * A process service alone on its cluster, so a probe goes straight to its command instead
   * of asking the query API which scope the key belongs to. Its pre-commit checks are kept
   * rather than run, which is what the two checks above read.
   */
  private Camunda8ProcessService<Object> aService() {

    final var configuration = new Camunda8AdapterConfiguration();
    // an address nothing ever contacts - every request of this test meets the mock above
    configuration.setRestAddress("http://localhost:1");
    configuration.setWorkflowVisibilityTimeout(Duration.ZERO);
    final var clientFactory = new Camunda8ClientFactory("c8", configuration) {

      @Override
      public CamundaClient getClient() {
        return client;
      }

      @Override
      public boolean sharesItsCluster() {
        return false;
      }

    };
    final PreCommitRegistrar registrar = (
        aggregateClass,
        check) -> preCommitChecks.add(check);
    return new Camunda8ProcessService<>("c8", clientFactory, Duration.ofDays(14), registrar, null);

  }

  private void theClusterAnswersTheUpdateWith(
      final RuntimeException rejection) {

    final var command = mock(UpdateUserTaskCommandStep1.class, RETURNS_SELF);
    Mockito.lenient().when(command.send()).thenThrow(rejection);
    Mockito.lenient().when(client.newUpdateUserTaskCommand(Mockito.anyLong())).thenReturn(command);

  }

  /**
   * A cluster which refuses the completion as often as there are rejections and takes it
   * afterwards.
   *
   * @param rejections What the cluster answers, attempt by attempt
   * @return The command, so a test can count what was sent
   */
  private CompleteUserTaskCommandStep1 theClusterAnswersTheCompletionWith(
      final RuntimeException... rejections) {

    final var command = mock(CompleteUserTaskCommandStep1.class, RETURNS_SELF);
    // the future is built BEFORE the stubbing which returns it - mocking inside a
    // when(...) leaves Mockito with an unfinished stubbing
    @SuppressWarnings("unchecked")
    final CamundaFuture<CompleteUserTaskResponse> taken = mock(CamundaFuture.class);
    var stubbing = Mockito.lenient().when(command.send());
    for (final var rejection : rejections) {
      stubbing = stubbing.thenThrow(rejection);
    }
    stubbing.thenReturn(taken);
    Mockito
        .lenient()
        .when(client.newCompleteUserTaskCommand(Mockito.anyLong()))
        .thenReturn(command);
    return command;

  }

}
