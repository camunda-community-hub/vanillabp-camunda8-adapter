package io.vanillabp.camunda8.processservice;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.CamundaFuture;
import io.camunda.client.api.ProblemDetail;
import io.camunda.client.api.command.ProblemException;
import io.camunda.client.api.command.UpdateUserTaskCommandStep1;
import io.camunda.client.api.fetch.UserTaskGetRequest;
import io.camunda.client.api.search.enums.UserTaskState;
import io.camunda.client.api.search.response.UserTask;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.integration.adapter.spi.PreCommitRegistrar;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.TaskNotFoundException;

/**
 * Completing a user task the cluster is already ending.
 * <p>
 * The engine answers the empty update of the pre-commit check with HTTP <code>409</code> for a
 * task in <code>CANCELING</code> or <code>COMPLETING</code>. That is the same answer as for a task
 * which is still being created. So after a <code>409</code> the check asks the search for the
 * state of the task. In the states of an ending task the caller's transaction rolls back, as in
 * version 1. In every other state, and where the search cannot answer, it commits as before. Why
 * is decision 83 in the repository's DECISIONS.md.
 * <p>
 * {@code Camunda8UserTaskAlreadyEndingIT} forces both states against a real cluster.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8UserTaskAlreadyEndingTest {

  private static final String TASK_ID = "2251799813685249";

  private static final EnumSet<UserTaskState> ENDING = EnumSet
      .of(UserTaskState.CANCELING, UserTaskState.CANCELED, UserTaskState.COMPLETING, UserTaskState.COMPLETED);

  private final CamundaClient client = mock(CamundaClient.class);

  private final List<Runnable> preCommitChecks = new ArrayList<>();

  private static ProblemException refusedInState(
      final String state) {

    final var details = new ProblemDetail();
    details.setStatus(409);
    details.setTitle("INVALID_STATE");
    return new ProblemException(
        409, "Expected to update user task with key '%s', but it is in state '%s'".formatted(TASK_ID, state), details);

  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(UserTaskState.class)
  @DisplayName("After a 409, the state the search reports decides whether the transaction rolls back")
  public void theStateDecides(
      final UserTaskState state) {

    theClusterRefusesTheUpdate(refusedInState(String.valueOf(state)));
    theSearchReports(state);

    registerThePreCommitCheckOfACompletion();

    if (ENDING.contains(state)) {
      final var thrown = assertThrows(
          TaskNotFoundException.class,
          () -> preCommitChecks.getFirst().run(),
          "a task the cluster is ending cannot be completed any more, so the caller's transaction "
              + "rolls back");
      assertTrue(
          thrown.getMessage().contains(TASK_ID) && thrown.getMessage().contains(state.name()),
          "the message names the task and its state: "
              + thrown.getMessage());
    } else {
      assertDoesNotThrow(
          () -> preCommitChecks.getFirst().run(),
          "in every other state the task is still there, and the completion is sent after the commit");
    }

  }

  @Test
  @DisplayName("A search which cannot answer leaves the answer of the engine standing")
  public void aSearchWhichFailsChangesNothing() {

    theClusterRefusesTheUpdate(refusedInState("CREATING"));
    final var request = mock(UserTaskGetRequest.class, RETURNS_SELF);
    Mockito.when(request.send()).thenThrow(new ProblemException(404, "not yet in the search", new ProblemDetail()));
    Mockito.when(client.newUserTaskGetRequest(Mockito.anyLong())).thenReturn(request);

    registerThePreCommitCheckOfACompletion();

    assertDoesNotThrow(
        () -> preCommitChecks.getFirst().run(),
        "a task which is still being created may not be in the search yet, and it must not be "
            + "turned away for that");

  }

  @Test
  @DisplayName("An open task is not looked up in the search at all")
  public void anOpenTaskCostsNoSearch() {

    final var command = mock(UpdateUserTaskCommandStep1.class, RETURNS_SELF);
    Mockito.when(command.send()).thenReturn(mock(CamundaFuture.class));
    Mockito.when(client.newUpdateUserTaskCommand(Mockito.anyLong())).thenReturn(command);

    registerThePreCommitCheckOfACompletion();
    preCommitChecks.getFirst().run();

    Mockito.verify(client, Mockito.never()).newUserTaskGetRequest(Mockito.anyLong());

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

  private Camunda8ProcessService<Object> aService() {

    final var configuration = new Camunda8AdapterConfiguration();
    // nothing ever contacts this address, every request of this test meets the mock above
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

  private void theClusterRefusesTheUpdate(
      final RuntimeException rejection) {

    final var command = mock(UpdateUserTaskCommandStep1.class, RETURNS_SELF);
    Mockito.when(command.send()).thenThrow(rejection);
    Mockito.when(client.newUpdateUserTaskCommand(Mockito.anyLong())).thenReturn(command);

  }

  @SuppressWarnings("unchecked")
  private void theSearchReports(
      final UserTaskState state) {

    final var userTask = mock(UserTask.class);
    Mockito.when(userTask.getState()).thenReturn(state);
    final CamundaFuture<UserTask> future = mock(CamundaFuture.class);
    Mockito.when(future.join()).thenReturn(userTask);
    final var request = mock(UserTaskGetRequest.class, RETURNS_SELF);
    Mockito.when(request.send()).thenReturn(future);
    Mockito.when(client.newUserTaskGetRequest(Mockito.anyLong())).thenReturn(request);

  }

}
