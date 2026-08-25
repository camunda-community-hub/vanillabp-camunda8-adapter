package io.vanillabp.camunda8.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.camunda.client.api.command.ProblemException;
import io.camunda.client.api.search.enums.UserTaskState;
import io.camunda.client.api.search.response.UserTask;
import io.vanillabp.camunda8.service.Camunda8TransactionProcessor.Camunda8CommandAfterTx;
import io.vanillabp.camunda8.service.Camunda8TransactionProcessor.Camunda8TestForTaskAlreadyCompletedOrCancelled;
import io.vanillabp.camunda8.service.Camunda8TransactionProcessor.TaskAlreadyCompletedOrCancelledException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Covers the guard which keeps a user task that already ended from being completed, and the reporting
 * of a failed post-commit command.
 *
 * <p>Background: completing a Zeebe user task is deferred to after the commit, so its failure can no
 * longer reach the caller - Spring logs whatever {@code afterCompletion} throws and moves on.
 * The guard therefore runs <i>before</i> the commit and has to roll the transaction back while there
 * still is one.
 *
 * <p>The trap it was missing: "GET /v2/user-tasks/&lt;key&gt;" is answered from the secondary storage,
 * which keeps user tasks after they ended. A task cancelled by a process instance modification is
 * still returned - with a changed state - so testing for a NOT_FOUND alone lets the transaction
 * commit and the completion fail afterwards.
 */
class Camunda8UserTaskStateGuardTest {

    private static final String TASK_ID = "6755401077443876";

    private static UserTask userTaskInState(
            final UserTaskState state) {

        final var userTask = mock(UserTask.class);
        when(userTask.getState()).thenReturn(state);
        return userTask;

    }

    @ParameterizedTest
    @EnumSource(value = UserTaskState.class, names = { "CANCELING", "CANCELED", "COMPLETING", "COMPLETED" })
    void endedTaskIsRejected(
            final UserTaskState state) {

        assertThatThrownBy(() -> Camunda8ProcessService.testZeebeUserTaskCanStillBeActedOn(
                        userTaskInState(state), TASK_ID))
                .isInstanceOf(TaskAlreadyCompletedOrCancelledException.class)
                .hasMessageContaining(TASK_ID)
                .hasMessageContaining(state.name());

    }

    @ParameterizedTest
    @EnumSource(value = UserTaskState.class, names = { "CREATING", "CREATED", "ASSIGNING", "UPDATING",
            "FAILED", "UNKNOWN_ENUM_VALUE" })
    void taskWhichCanStillBeActedOnPasses(
            final UserTaskState state) {

        assertThatCode(() -> Camunda8ProcessService.testZeebeUserTaskCanStillBeActedOn(
                        userTaskInState(state), TASK_ID))
                .doesNotThrowAnyException();

    }

    /** Neither is anything the guard could judge, so it must not stand in the way. */
    @Test
    void unknownTaskAndUnknownStatePass() {

        assertThatCode(() -> Camunda8ProcessService.testZeebeUserTaskCanStillBeActedOn(null, TASK_ID))
                .doesNotThrowAnyException();
        assertThatCode(() -> Camunda8ProcessService.testZeebeUserTaskCanStillBeActedOn(
                        userTaskInState(null), TASK_ID))
                .doesNotThrowAnyException();

    }

    /**
     * An ended task is not a missing one: there is nothing the fallback (which addresses the task as a
     * job) could still achieve, so it must not run - the transaction has to be rolled back instead.
     */
    @Test
    void preCommitRollsBackOnEndedTaskWithoutRunningTheFallback() {

        final var fallbackRan = new AtomicBoolean(false);
        final var cause = new TaskAlreadyCompletedOrCancelledException("task is in state 'CANCELED'");

        final var event = new Camunda8TestForTaskAlreadyCompletedOrCancelled(
                "completeUserTask",
                () -> { throw cause; },
                () -> fallbackRan.set(true),
                () -> "UserTaskGet on '" + TASK_ID + "'");

        assertThatThrownBy(() -> new Camunda8TransactionProcessor().processPreCommit(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Will rollback")
                .hasMessageContaining("completeUserTask")
                .hasCause(cause);
        assertThat(fallbackRan).isFalse();

    }

    /** A missing task, on the other hand, may well be a job-based one - regression guard. */
    @Test
    void preCommitStillFallsBackOnNotFound() {

        final var fallbackRan = new AtomicBoolean(false);

        final var event = new Camunda8TestForTaskAlreadyCompletedOrCancelled(
                "completeUserTask",
                () -> { throw notFound(); },
                () -> fallbackRan.set(true),
                () -> "UserTaskGet on '" + TASK_ID + "'");

        assertThatCode(() -> new Camunda8TransactionProcessor().processPreCommit(event))
                .doesNotThrowAnyException();
        assertThat(fallbackRan).isTrue();

    }

    /**
     * If the fallback fails too, it is its exception which explains why nothing worked - the NOT_FOUND
     * that made it run is kept as suppressed rather than being reported as the cause.
     */
    @Test
    void postCommitReportsTheFallbacksFailureAndKeepsTheOriginal() {

        final var notFound = notFound();
        final var fallbackFailure = new IllegalStateException("job does not exist either");

        final var event = new Camunda8CommandAfterTx(
                "completeUserTask",
                () -> { throw notFound; },
                () -> { throw fallbackFailure; },
                () -> "aggregate: DITX-APPR-382");

        assertThatThrownBy(() -> new Camunda8TransactionProcessor().processPostCommit(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Manual action required!")
                .hasCause(fallbackFailure)
                .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(notFound));

    }

    @Test
    void postCommitReportsTheOriginalFailureWhenThereIsNoFallback() {

        final var failure = new IllegalStateException("boom");

        final var event = new Camunda8CommandAfterTx(
                "completeUserTask",
                () -> { throw failure; },
                null,
                () -> "aggregate: DITX-APPR-382");

        assertThatThrownBy(() -> new Camunda8TransactionProcessor().processPostCommit(event))
                .isInstanceOf(RuntimeException.class)
                .hasCause(failure)
                .satisfies(thrown -> assertThat(thrown.getSuppressed()).isEmpty());

    }

    @Test
    void postCommitStaysSilentWhenTheFallbackSucceeds() {

        final var fallbackRan = new AtomicBoolean(false);

        final var event = new Camunda8CommandAfterTx(
                "completeUserTask",
                () -> { throw notFound(); },
                () -> fallbackRan.set(true),
                () -> "aggregate: DITX-APPR-382");

        assertThatCode(() -> new Camunda8TransactionProcessor().processPostCommit(event))
                .doesNotThrowAnyException();
        assertThat(fallbackRan).isTrue();

    }

    private static ProblemException notFound() {

        return new ProblemException(404, "Not Found", null);

    }

}
