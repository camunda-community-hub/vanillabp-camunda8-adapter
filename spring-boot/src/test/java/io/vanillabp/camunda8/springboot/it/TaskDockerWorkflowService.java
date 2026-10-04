package io.vanillabp.camunda8.springboot.it;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskException;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * Workflow service of the task-processing integration test: one
 * {@code @WorkflowTask} method per outcome variation, serving three BPMN processes
 * of one aggregate. Handlers record invocation counts so the test can prove
 * at-least-once redelivery convergence and async-task dormancy.
 */
@Service
@WorkflowService(
    workflowAggregateClass = TaskDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "TaskProcess"),
    secondaryBpmnProcesses = {
        @BpmnProcess(bpmnProcessId = "FailProcess"), @BpmnProcess(
            bpmnProcessId = "ModelledBackoffProcess"), @BpmnProcess(bpmnProcessId = "AsyncProcess"), @BpmnProcess(
                bpmnProcessId = "RetryProcess"), @BpmnProcess(bpmnProcessId = "AsyncCancelProcess"), @BpmnProcess(
                    bpmnProcessId = "UserTaskProcess"), @BpmnProcess(
                        bpmnProcessId = "SilentUserTaskProcess"), @BpmnProcess(
                            bpmnProcessId = "MessageProcess"), @BpmnProcess(
                                bpmnProcessId = "SyncProcess"), @BpmnProcess(bpmnProcessId = "FetchProcess")
    })
public class TaskDockerWorkflowService {

  /**
   * Invocation counters per (task definition + aggregate ID) - inspected by the
   * integration test.
   */
  public static final Map<String, AtomicInteger> INVOCATIONS = new ConcurrentHashMap<>();

  /**
   * When each invocation happened, per (task definition + aggregate ID) - what the
   * retry-backoff test measures the distance between two deliveries with.
   */
  public static final Map<String, List<Long>> INVOCATION_TIMES = new ConcurrentHashMap<>();

  private final ProcessService<TaskDockerAggregate> processService;

  public TaskDockerWorkflowService(
      final ProcessService<TaskDockerAggregate> processService) {

    this.processService = processService;

  }

  public TaskDockerAggregate startWorkflow(
      final TaskDockerAggregate aggregate) {

    return processService.startWorkflow(aggregate);

  }

  private static int countInvocation(
      final String taskDefinition,
      final TaskDockerAggregate aggregate) {

    final var key = taskDefinition
        + ":"
        + aggregate.getId();
    INVOCATION_TIMES
        .computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()))
        .add(System.currentTimeMillis());
    return INVOCATIONS
        .computeIfAbsent(key, k -> new AtomicInteger())
        .incrementAndGet();

  }

  @WorkflowTask
  public void happyTask(
      final TaskDockerAggregate aggregate) {

    countInvocation("happyTask", aggregate);
    // idempotent: keyed on aggregate state, not on call count
    if ((aggregate.getResults() == null) || !aggregate.getResults().contains("happy")) {
      aggregate.appendResult("happy");
    }

  }

  @WorkflowTask
  public void retryTask(
      final TaskDockerAggregate aggregate) {

    // the FIRST delivery fails technically (local transaction rolled back, job
    // failed with decremented retries) - Camunda 8 REDELIVERS the same task and
    // the second delivery converges idempotently: at-least-once proven
    if (countInvocation("retryTask", aggregate) == 1) {
      throw new IllegalStateException("first delivery fails - forcing a redelivery");
    }
    if ((aggregate.getResults() == null) || !aggregate.getResults().contains("retried")) {
      aggregate.appendResult("retried");
    }

  }

  @WorkflowTask
  public void errorTask(
      final TaskDockerAggregate aggregate) {

    countInvocation("errorTask", aggregate);
    // the mutation has to be COMMITTED although the handler throws - the V1
    // TaskException contract (BPMN error, no rollback)
    aggregate.appendResult("error-raised");
    throw new TaskException("PaymentFailed", "PAYMENT_FAILED");

  }

  @WorkflowTask
  public void errorHandled(
      final TaskDockerAggregate aggregate) {

    countInvocation("errorHandled", aggregate);
    aggregate.appendResult("handled");

  }

  @WorkflowTask
  public void alwaysFails(
      final TaskDockerAggregate aggregate) {

    countInvocation("alwaysFails", aggregate);
    // must NEVER become visible: a technical exception rolls back the local
    // transaction; the job is failed with decremented retries
    aggregate.appendResult("must-never-be-visible");
    throw new IllegalStateException("boom-c8");

  }

  @WorkflowTask
  public void modelledBackoffFails(
      final TaskDockerAggregate aggregate) {

    countInvocation("modelledBackoffFails", aggregate);
    // its element carries the task header 'retryBackoff', which is what decides how
    // long the cluster waits before the next delivery
    throw new IllegalStateException("boom-modelled-backoff");

  }

  public TaskDockerAggregate completeAsyncTask(
      final TaskDockerAggregate aggregate,
      final String taskId) {

    return processService.completeTask(aggregate, taskId);

  }

  public TaskDockerAggregate cancelAsyncTask(
      final TaskDockerAggregate aggregate,
      final String taskId,
      final String bpmnErrorCode) {

    return processService.cancelTask(aggregate, taskId, bpmnErrorCode);

  }

  @WorkflowTask
  public void awaitCancelTask(
      final TaskDockerAggregate aggregate,
      @TaskId final String taskId) {

    countInvocation("awaitCancelTask", aggregate);
    aggregate.setTaskId(taskId);
    aggregate.appendResult("await-cancel");

  }

  @WorkflowTask
  public void cancelHandled(
      final TaskDockerAggregate aggregate) {

    countInvocation("cancelHandled", aggregate);
    aggregate.appendResult("cancel-handled");

  }

  public TaskDockerAggregate completeUserTask(
      final TaskDockerAggregate aggregate,
      final String taskId) {

    return processService.completeUserTask(aggregate, taskId);

  }

  public TaskDockerAggregate cancelUserTask(
      final TaskDockerAggregate aggregate,
      final String taskId,
      final String bpmnErrorCode) {

    return processService.cancelUserTask(aggregate, taskId, bpmnErrorCode);

  }

  @WorkflowTask(taskDefinition = "approveUser")
  public void approveUserNotification(
      final TaskDockerAggregate aggregate,
      @TaskId final String taskId,
      @TaskEvent final TaskEvent.Event event) {

    countInvocation("approveUser", aggregate);
    if (event == TaskEvent.Event.CREATED) {
      aggregate.setTaskId(taskId);
      aggregate.appendResult("usertask-created");
    } else {
      aggregate.appendResult("usertask-"
          + event.name().toLowerCase());
    }

  }

  public TaskDockerAggregate correlate(
      final TaskDockerAggregate aggregate,
      final String messageName) {

    return processService.correlateMessage(aggregate, messageName);

  }

  public TaskDockerAggregate correlate(
      final TaskDockerAggregate aggregate,
      final String messageName,
      final String correlationId) {

    return processService.correlateMessage(aggregate, messageName, correlationId);

  }

  public TaskDockerAggregate startByMessage(
      final TaskDockerAggregate aggregate,
      final String messageName) {

    return processService.startWorkflowByMessage(aggregate, messageName);

  }

  @WorkflowTask
  public void c8MessageArrived(
      final TaskDockerAggregate aggregate) {

    countInvocation("c8MessageArrived", aggregate);
    aggregate.appendResult("message-arrived");

  }

  /**
   * The variables the cluster delivered to {@code syncApproved} - i.e. what the
   * completion of {@code syncTask} pushed.
   */
  public static final Map<String, Object> OBSERVED_VARIABLES = new ConcurrentHashMap<>();

  @WorkflowTask
  public void syncTask(
      final TaskDockerAggregate aggregate) {

    countInvocation("syncTask", aggregate);
    // both changes happen AFTER the workflow was started, so the cluster can only
    // know them if the job completion pushes the aggregate state
    aggregate.setApproved(true);
    aggregate.setSecret("s3cr3t");
    aggregate.appendResult("sync-task");

  }

  /**
   * Reads what the cluster holds, whatever type that is: the three parameters are
   * declared as <code>Object</code> because the point of this task is the cluster's
   * view of the variables, not a type the application expects. The test application
   * therefore declares the three names in <code>camunda8-it.yaml</code>, at the
   * workflow module, because this one workflow service serves twelve processes.
   *
   * @param aggregate The workflow aggregate
   * @param approved What the cluster holds for the approval
   * @param results What the cluster holds for the results
   * @param secret What the cluster holds for the secret
   */
  @WorkflowTask
  public void syncApproved(
      final TaskDockerAggregate aggregate,
      @TaskParam("approved") final Object approved,
      @TaskParam("results") final Object results,
      @TaskParam("secret") final Object secret) {

    countInvocation("syncApproved", aggregate);
    // @TaskParam reads the variables of the delivered job: the cluster's view
    OBSERVED_VARIABLES.put("approved", String.valueOf(approved));
    OBSERVED_VARIABLES.put("results", String.valueOf(results));
    OBSERVED_VARIABLES.put("secret", String.valueOf(secret));
    aggregate.appendResult("sync-approved");

  }

  @WorkflowTask
  public void syncRejected(
      final TaskDockerAggregate aggregate) {

    countInvocation("syncRejected", aggregate);
    // reached only if the gateway evaluated STALE data
    aggregate.appendResult("sync-rejected");

  }

  /**
   * The escape hatch at TASK level: this task is configured with
   * {@code fetch-variables: all}, so its worker asks the cluster for the complete
   * variable scope. The derived list would answer this {@code @TaskParam} as well - what the task proves is that the property still reaches the worker and that
   * a worker asking for everything keeps working.
   */
  @WorkflowTask
  public void fetchAllTask(
      final TaskDockerAggregate aggregate,
      @TaskParam("bigPayload") final String bigPayload) {

    countInvocation("fetchAllTask", aggregate);
    OBSERVED_VARIABLES.put("bigPayloadLength", bigPayload == null
        ? -1
        : bigPayload.length());
    aggregate.appendResult("fetch-all");

  }

  /**
   * The default: nothing is configured for this task and {@code bigPayload}
   * appears in no BPMN model - it was handed to the workflow when it was started. Its
   * worker still asks the cluster for that variable, because the core reports the
   * {@code @TaskParam} names of the methods serving a task while the application wires
   * itself.
   */
  @WorkflowTask
  public void fetchDerivedTask(
      final TaskDockerAggregate aggregate,
      @TaskParam("bigPayload") final String bigPayload) {

    countInvocation("fetchDerivedTask", aggregate);
    OBSERVED_VARIABLES.put("derivedPayloadLength", bigPayload == null
        ? -1
        : bigPayload.length());
    aggregate.appendResult("fetch-derived");

  }

  /**
   * A method wired to the ELEMENT id instead of to the task definition. The job type of
   * this task is named after nothing in this class, so only {@code id = "FE_byId"}
   * reaches the method, which is what this test is here for.
   * <p>
   * It declares a {@code @TaskParam} as well, and the worker has to ask the cluster for
   * {@code bigPayload} all the same: the fetch list is derived from both keys, because
   * asking for the job type alone left this method without its variable and the worker
   * failed the job rather than passing null.
   */
  @WorkflowTask(id = "FE_byId")
  public void fetchByElementId(
      final TaskDockerAggregate aggregate,
      @TaskParam("bigPayload") final String bigPayload) {

    countInvocation("fetchByElementId", aggregate);
    OBSERVED_VARIABLES.put("byElementIdPayloadLength", bigPayload == null
        ? -1
        : bigPayload.length());
    aggregate.appendResult("fetch-by-element-id");

  }

  @WorkflowTask
  public void asyncTask(
      final TaskDockerAggregate aggregate,
      @TaskId final String taskId) {

    countInvocation("asyncTask", aggregate);
    aggregate.setTaskId(taskId);
    aggregate.appendResult("async-open");

  }

}
