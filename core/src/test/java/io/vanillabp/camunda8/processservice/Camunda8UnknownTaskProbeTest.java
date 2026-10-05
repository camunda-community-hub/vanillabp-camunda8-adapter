package io.vanillabp.camunda8.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.CamundaFuture;
import io.camunda.client.api.command.ClientHttpException;
import io.camunda.client.api.command.UpdateTimeoutJobCommandStep1;
import io.camunda.client.api.command.UpdateUserTaskCommandStep1;
import io.camunda.client.api.fetch.UserTaskGetRequest;
import io.camunda.client.api.search.request.JobSearchRequest;
import io.camunda.client.api.search.response.Job;
import io.camunda.client.api.search.response.SearchResponse;
import io.camunda.client.api.search.response.UserTask;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Why a task probe answered <code>UNKNOWN_TO_BPMS</code>, in the log.
 * <p>
 * A task is an exact question, so the platform turns that answer into a
 * <code>WorkflowNotFoundException</code> at once, with no visibility window and no second
 * attempt. One red run of the release line 8.8 left exactly that exception and not a word
 * from the adapter, and the two branches which can produce the answer are told apart by
 * nothing else: the job was found in ANOTHER scope, or the cluster refused the probe's
 * command as a job it no longer has.
 * <p>
 * Both branches say so at INFO now, naming the key and the values which decided it. What the
 * probe ANSWERS is unchanged, which is why every test here asserts the answer next to the
 * line.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8UnknownTaskProbeTest {

  private static final WorkflowScope SCOPE = WorkflowScope.of("test-module", "TestProcess");

  private static final String TASK_ID = "2251799813685249";

  private final CamundaClient client = mock(CamundaClient.class);

  /**
   * A process service whose cluster is addressed by a second adapter id, which is when the
   * probe asks the query API which scope a key belongs to.
   */
  private Camunda8ProcessService<?> aServiceSharingItsCluster() {

    return serviceOf(true);

  }

  /**
   * A process service alone on its cluster, so the probe goes straight to its command.
   */
  private Camunda8ProcessService<?> aServiceAloneOnItsCluster() {

    return serviceOf(false);

  }

  private Camunda8ProcessService<?> serviceOf(
      final boolean shared) {

    final var configuration = new Camunda8AdapterConfiguration();
    // an address nothing ever contacts - every request of this test meets the mock above
    configuration.setRestAddress("http://localhost:1");
    // no waiting for the exporter in a unit test - the cluster is never contacted
    configuration.setWorkflowVisibilityTimeout(Duration.ZERO);
    final var clientFactory = new Camunda8ClientFactory("c8", configuration) {

      @Override
      public CamundaClient getClient() {
        return client;
      }

      @Override
      public boolean sharesItsCluster() {
        return shared;
      }

    };
    return new Camunda8ProcessService<Object>(
        "c8", clientFactory, Duration.ofDays(14), (
            aggregateClass,
            check) -> check.run(), null);

  }

  @Test
  @DisplayName("A job found in another scope is named with both scopes")
  public void theScopeBranchNamesWhatWasFoundAndWhatWasAsked() {

    theClusterKnowsTheJobOf("other-module-TestProcess", "<default>");

    final var lines = whatWasLoggedWhile(
        () -> assertEquals(
            WorkflowAwareness.UNKNOWN_TO_BPMS,
            aServiceSharingItsCluster().awarenessOfTask(SCOPE, "agg-1", TASK_ID),
            "the answer of a task belonging to another adapter id is unchanged"));

    assertTrue(
        lines.stream().anyMatch(line -> line.contains(TASK_ID) && line
            .contains("other-module-TestProcess") && line.contains("TestProcess")),
        () -> "expected one line naming the key, the scope found and the scope asked about, "
            + "but saw: "
            + lines);

  }

  @Test
  @DisplayName("A user task found in another scope is named the same way")
  public void theScopeBranchOfAUserTaskSaysTheSame() {

    theClusterKnowsTheUserTaskOf("other-module-TestProcess", "<default>");

    final var lines = whatWasLoggedWhile(
        () -> assertEquals(
            WorkflowAwareness.UNKNOWN_TO_BPMS,
            aServiceSharingItsCluster().awarenessOfUserTask(SCOPE, "agg-1", TASK_ID),
            "the answer is unchanged here as well"));

    assertTrue(
        lines
            .stream()
            .anyMatch(line -> line.contains(TASK_ID) && line.contains("other-module-TestProcess")),
        () -> "expected the scope the cluster reported for the user task, but saw: "
            + lines);

  }

  @Test
  @DisplayName("A refused UpdateJobTimeout is named with the cluster's code and reason")
  public void theGoneBranchNamesTheRejection() {

    theClusterRefusesTheJobTimeoutUpdate(
        new ClientHttpException("Failed with code 404", 404, "job not found"));

    final var lines = whatWasLoggedWhile(
        () -> assertEquals(
            WorkflowAwareness.UNKNOWN_TO_BPMS,
            aServiceAloneOnItsCluster().awarenessOfTask(SCOPE, "agg-1", TASK_ID),
            "a job the cluster does not have is unknown to it, as before"));

    assertTrue(
        lines
            .stream()
            .anyMatch(line -> line.contains(TASK_ID) && line.contains("404") && line
                .contains("job not found")),
        () -> "expected the key and what the cluster refused the probe with, but saw: "
            + lines);

  }

  @Test
  @DisplayName("A refused UpdateUserTask is named the same way")
  public void theGoneBranchOfAUserTaskNamesTheRejection() {

    theClusterRefusesTheUserTaskUpdate(
        new ClientHttpException("Failed with code 404", 404, "user task not found"));

    final var lines = whatWasLoggedWhile(
        () -> assertEquals(
            WorkflowAwareness.UNKNOWN_TO_BPMS,
            aServiceAloneOnItsCluster().awarenessOfUserTask(SCOPE, "agg-1", TASK_ID),
            "and the answer stays what it was"));

    assertTrue(
        lines
            .stream()
            .anyMatch(line -> line.contains(TASK_ID) && line.contains("404") && line
                .contains("user task not found")),
        () -> "expected the key and the cluster's rejection, but saw: "
            + lines);

  }

  /**
   * A cluster whose job search answers with a job of the given scope.
   */
  private void theClusterKnowsTheJobOf(
      final String processDefinitionId,
      final String tenantId) {

    final var job = mock(Job.class);
    Mockito.lenient().when(job.getProcessDefinitionId()).thenReturn(processDefinitionId);
    Mockito.lenient().when(job.getTenantId()).thenReturn(tenantId);
    final var search = mock(JobSearchRequest.class, RETURNS_SELF);
    @SuppressWarnings("unchecked")
    final SearchResponse<Job> found = mock(SearchResponse.class);
    Mockito.lenient().when(found.items()).thenReturn(List.of(job));
    // the future is built BEFORE the stubbing which returns it - mocking inside a
    // when(...) leaves Mockito with an unfinished stubbing
    final var answer = future(found);
    Mockito.lenient().when(search.send()).thenReturn(answer);
    Mockito.lenient().when(client.newJobSearchRequest()).thenReturn(search);

  }

  /**
   * A cluster whose user-task request answers with a task of the given scope.
   */
  private void theClusterKnowsTheUserTaskOf(
      final String bpmnProcessId,
      final String tenantId) {

    final var task = mock(UserTask.class);
    Mockito.lenient().when(task.getBpmnProcessId()).thenReturn(bpmnProcessId);
    Mockito.lenient().when(task.getTenantId()).thenReturn(tenantId);
    final var request = mock(UserTaskGetRequest.class, RETURNS_SELF);
    final var answer = future(task);
    Mockito.lenient().when(request.send()).thenReturn(answer);
    Mockito.lenient().when(client.newUserTaskGetRequest(Mockito.anyLong())).thenReturn(request);

  }

  private void theClusterRefusesTheJobTimeoutUpdate(
      final RuntimeException rejection) {

    final var command = mock(
        UpdateTimeoutJobCommandStep1.UpdateTimeoutJobCommandStep2.class,
        RETURNS_SELF);
    Mockito.lenient().when(command.send()).thenThrow(rejection);
    final var step1 = mock(UpdateTimeoutJobCommandStep1.class, RETURNS_SELF);
    Mockito.lenient().when(step1.timeout(Mockito.any(Duration.class))).thenReturn(command);
    Mockito.lenient().when(client.newUpdateTimeoutCommand(Mockito.anyLong())).thenReturn(step1);

  }

  private void theClusterRefusesTheUserTaskUpdate(
      final RuntimeException rejection) {

    final var command = mock(UpdateUserTaskCommandStep1.class, RETURNS_SELF);
    Mockito.lenient().when(command.send()).thenThrow(rejection);
    Mockito.lenient().when(client.newUpdateUserTaskCommand(Mockito.anyLong())).thenReturn(command);

  }

  /**
   * The INFO lines the adapter wrote while the given probe ran.
   */
  private static List<String> whatWasLoggedWhile(
      final Runnable probe) {

    final var logWatcher = new ListAppender<ILoggingEvent>();
    logWatcher.start();
    final var adapterLog = (Logger) LoggerFactory.getLogger(Camunda8ProcessService.class);
    adapterLog.addAppender(logWatcher);
    try {
      probe.run();
    } finally {
      adapterLog.detachAppender(logWatcher);
    }
    return logWatcher.list
        .stream()
        .filter(event -> event.getLevel() == Level.INFO)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();

  }

  private static <T> CamundaFuture<T> future(
      final T value) {

    @SuppressWarnings("unchecked")
    final CamundaFuture<T> future = mock(CamundaFuture.class);
    Mockito.lenient().when(future.join()).thenReturn(value);
    return future;

  }

}
