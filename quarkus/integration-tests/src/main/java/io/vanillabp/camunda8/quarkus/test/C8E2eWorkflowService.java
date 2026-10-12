package io.vanillabp.camunda8.quarkus.test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.vanillabp.spi.process.ProcessDefinition;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.process.WorkflowHistory;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.MultiInstanceElement;
import io.vanillabp.spi.service.MultiInstanceIndex;
import io.vanillabp.spi.service.MultiInstanceTotal;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskException;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The workflow service of the Quarkus end-to-end application. It has one
 * <code>&#64;WorkflowTask</code> method per outcome and per way of binding parameters. It
 * serves the same BPMN models the Spring Boot integration tests use.
 * <p>
 * Both platforms run the same set of documented features on purpose. A correct core does
 * not prove that a platform's glue code ever calls it. This is also why coverage is
 * measured per platform.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = C8E2eAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "TaskProcess"),
    secondaryBpmnProcesses = {
        @BpmnProcess(bpmnProcessId = "FailProcess"), @BpmnProcess(bpmnProcessId = "RetryProcess"), @BpmnProcess(
            bpmnProcessId = "ModelledBackoffProcess"), @BpmnProcess(
                bpmnProcessId = "AsyncProcess"), @BpmnProcess(bpmnProcessId = "AsyncCancelProcess"), @BpmnProcess(
                    bpmnProcessId = "UserTaskProcess"), @BpmnProcess(
                        bpmnProcessId = "SilentUserTaskProcess"), @BpmnProcess(
                            bpmnProcessId = "MessageProcess"), @BpmnProcess(
                                bpmnProcessId = "SyncProcess"), @BpmnProcess(
                                    bpmnProcessId = "FetchProcess"), @BpmnProcess(
                                        bpmnProcessId = "MultiInstanceProcess"), @BpmnProcess(
                                            bpmnProcessId = "SignalCatchProcess"), @BpmnProcess(
                                                bpmnProcessId = "VersionedProcess"), @BpmnProcess(
                                                    bpmnProcessId = "ConnectorProcess"), @BpmnProcess(
                                                        bpmnProcessId = "MiCallProcess"), @BpmnProcess(
                                                            bpmnProcessId = "MiCalledProcess"), @BpmnProcess(
                                                                bpmnProcessId = "MiGrandChildProcess")
    })
public class C8E2eWorkflowService {

  /**
   * How often a task definition was delivered, per task definition and aggregate id. The
   * assertions about redelivery and dormancy read this through the introspection
   * endpoints.
   */
  public static final Map<String, AtomicInteger> INVOCATIONS = new ConcurrentHashMap<>();

  /**
   * When each delivery happened. The retry-backoff assertion measures the time between two
   * deliveries with it.
   */
  public static final Map<String, List<Long>> INVOCATION_TIMES = new ConcurrentHashMap<>();

  /**
   * What the cluster delivered to a task as variables. This is the cluster's view of the
   * aggregate, not what the database holds.
   */
  public static final Map<String, String> OBSERVED_VARIABLES = new ConcurrentHashMap<>();

  @Inject
  ProcessService<C8E2eAggregate> processService;

  private static int countInvocation(
      final String taskDefinition,
      final C8E2eAggregate aggregate) {

    final var key = taskDefinition
        + ":"
        + aggregate.getId();
    INVOCATION_TIMES
        .computeIfAbsent(key, ignored -> Collections.synchronizedList(new ArrayList<>()))
        .add(System.currentTimeMillis());
    return INVOCATIONS
        .computeIfAbsent(key, ignored -> new AtomicInteger())
        .incrementAndGet();

  }

  /**
   * @param taskDefinition The task definition
   * @param aggregateId The aggregate
   * @return How often the task was delivered
   */
  public static int invocations(
      final String taskDefinition,
      final Object aggregateId) {

    final var counter = INVOCATIONS
        .get(taskDefinition
            + ":"
            + aggregateId);
    return counter == null
        ? 0
        : counter.get();

  }

  /**
   * @param taskDefinition The task definition
   * @param aggregateId The aggregate
   * @return The milliseconds between the first two deliveries, or -1
   */
  public static long deliveryGap(
      final String taskDefinition,
      final Object aggregateId) {

    final var times = INVOCATION_TIMES
        .get(taskDefinition
            + ":"
            + aggregateId);
    return (times == null) || (times.size() < 2)
        ? -1
        : times.get(1) - times.get(0);

  }

  // --- what the application asks of VanillaBP ---

  public C8E2eAggregate startWorkflow(
      final C8E2eAggregate aggregate) {

    return processService.startWorkflow(aggregate);

  }

  public C8E2eAggregate completeTask(
      final C8E2eAggregate aggregate,
      final String taskId) {

    return processService.completeTask(aggregate, taskId);

  }

  public C8E2eAggregate cancelTask(
      final C8E2eAggregate aggregate,
      final String taskId,
      final String bpmnErrorCode) {

    return processService.cancelTask(aggregate, taskId, bpmnErrorCode);

  }

  public C8E2eAggregate completeUserTask(
      final C8E2eAggregate aggregate,
      final String taskId) {

    return processService.completeUserTask(aggregate, taskId);

  }

  public C8E2eAggregate cancelUserTask(
      final C8E2eAggregate aggregate,
      final String taskId,
      final String bpmnErrorCode) {

    return processService.cancelUserTask(aggregate, taskId, bpmnErrorCode);

  }

  public C8E2eAggregate correlateMessage(
      final C8E2eAggregate aggregate,
      final String messageName) {

    return processService.correlateMessage(aggregate, messageName);

  }

  public C8E2eAggregate correlateMessage(
      final C8E2eAggregate aggregate,
      final String messageName,
      final String correlationId) {

    return processService.correlateMessage(aggregate, messageName, correlationId);

  }

  public C8E2eAggregate startWorkflowByMessage(
      final C8E2eAggregate aggregate,
      final String messageName) {

    return processService.startWorkflowByMessage(aggregate, messageName);

  }

  public void sendSignal(
      final String signalName) {

    processService.sendSignal(signalName);

  }

  public String getWorkflowModuleId() {

    return processService.getWorkflowModuleId();

  }

  /**
   * @return The viewer's process definitions of the given aggregate's workflow
   */
  public List<ProcessDefinition> processDefinitions(
      final C8E2eAggregate aggregate) {

    return processService.getProcessDefinitions(aggregate, null);

  }

  public InputStream bpmnXml(
      final String processDefinitionId) {

    return processService.getBpmnXml(processDefinitionId);

  }

  public WorkflowHistory workflowHistory(
      final C8E2eAggregate aggregate) {

    return processService.getWorkflowHistory(aggregate, null);

  }

  // --- what the cluster asks of the application ---

  @WorkflowTask
  public void happyTask(
      final C8E2eAggregate aggregate) {

    countInvocation("happyTask", aggregate);
    // idempotent: it depends on the aggregate's state, not on the number of deliveries
    if ((aggregate.getResults() == null) || !aggregate.getResults().contains("happy")) {
      aggregate.appendResult("happy");
    }

  }

  /**
   * The task BEHIND the connector element. It is never reached while nothing serves the
   * connector's job type. The connector test asserts exactly that: this application opened
   * no worker for a job type which belongs to somebody else's runtime.
   */
  @WorkflowTask
  public void afterConnector(
      final C8E2eAggregate aggregate) {

    countInvocation("afterConnector", aggregate);
    aggregate.appendResult("past-the-connector");

  }

  @WorkflowTask
  public void errorTask(
      final C8E2eAggregate aggregate) {

    countInvocation("errorTask", aggregate);
    // the change has to be COMMITTED although the handler throws. A TaskException is a
    // BPMN error, not a rollback
    aggregate.appendResult("error-raised");
    throw new TaskException("PaymentFailed", "PAYMENT_FAILED");

  }

  @WorkflowTask
  public void errorHandled(
      final C8E2eAggregate aggregate) {

    countInvocation("errorHandled", aggregate);
    aggregate.appendResult("handled");

  }

  @WorkflowTask
  public void alwaysFails(
      final C8E2eAggregate aggregate) {

    countInvocation("alwaysFails", aggregate);
    // must NEVER become visible. A technical exception rolls the job's local transaction
    // back and fails the job with one retry less
    aggregate.appendResult("must-never-be-visible");
    throw new IllegalStateException("boom-c8-quarkus-e2e");

  }

  @WorkflowTask
  public void modelledBackoffFails(
      final C8E2eAggregate aggregate) {

    countInvocation("modelledBackoffFails", aggregate);
    // its element carries the task header 'retryBackoff'. That header decides how long
    // the cluster waits before the next delivery
    throw new IllegalStateException("boom-modelled-backoff");

  }

  @WorkflowTask
  public void retryTask(
      final C8E2eAggregate aggregate) {

    // the FIRST delivery fails technically and the cluster delivers again. The second
    // delivery leads to the same result. This proves at-least-once delivery
    if (countInvocation("retryTask", aggregate) == 1) {
      throw new IllegalStateException("first delivery fails - forcing a redelivery");
    }
    if ((aggregate.getResults() == null) || !aggregate.getResults().contains("retried")) {
      aggregate.appendResult("retried");
    }

  }

  @WorkflowTask
  public void asyncTask(
      final C8E2eAggregate aggregate,
      @TaskId final String taskId) {

    countInvocation("asyncTask", aggregate);
    aggregate.setTaskId(taskId);
    aggregate.appendResult("async-open");

  }

  @WorkflowTask
  public void awaitCancelTask(
      final C8E2eAggregate aggregate,
      @TaskId final String taskId) {

    countInvocation("awaitCancelTask", aggregate);
    aggregate.setTaskId(taskId);
    aggregate.appendResult("await-cancel");

  }

  @WorkflowTask
  public void cancelHandled(
      final C8E2eAggregate aggregate) {

    countInvocation("cancelHandled", aggregate);
    aggregate.appendResult("cancel-handled");

  }

  @WorkflowTask(taskDefinition = "approveUser")
  public void approveUserNotification(
      final C8E2eAggregate aggregate,
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

  @WorkflowTask
  public void c8MessageArrived(
      final C8E2eAggregate aggregate) {

    countInvocation("c8MessageArrived", aggregate);
    aggregate.appendResult("message-arrived");

  }

  @WorkflowTask
  public void syncTask(
      final C8E2eAggregate aggregate) {

    countInvocation("syncTask", aggregate);
    // both changes happen AFTER the workflow was started. So the cluster only knows
    // them if the job completion pushes the aggregate's state
    aggregate.setApproved(true);
    aggregate.setSecret("s3cr3t");
    aggregate.appendResult("sync-task");

  }

  /**
   * Reads what the cluster holds, whatever type that is. The three parameters are
   * declared as <code>Object</code>. This task is about the cluster's view of the
   * variables, not about a type the application expects. So the test application declares
   * the three names in its <code>application.yaml</code>, at the workflow module. This
   * one workflow service serves every process of that module.
   *
   * @param aggregate The workflow aggregate
   * @param approved What the cluster holds for the approval
   * @param results What the cluster holds for the results
   * @param secret What the cluster holds for the secret
   */
  @WorkflowTask
  public void syncApproved(
      final C8E2eAggregate aggregate,
      @TaskParam("approved") final Object approved,
      @TaskParam("results") final Object results,
      @TaskParam("secret") final Object secret) {

    countInvocation("syncApproved", aggregate);
    // @TaskParam reads the variables of the delivered job, so this is the cluster's view
    OBSERVED_VARIABLES.put("approved", String.valueOf(approved));
    OBSERVED_VARIABLES.put("results", String.valueOf(results));
    OBSERVED_VARIABLES.put("secret", String.valueOf(secret));
    aggregate.appendResult("sync-approved");

  }

  @WorkflowTask
  public void syncRejected(
      final C8E2eAggregate aggregate) {

    countInvocation("syncRejected", aggregate);
    // reached only if the gateway evaluated OLD data
    aggregate.appendResult("sync-rejected");

  }

  /**
   * The first of two tasks reading {@code bigPayload}, a variable which appears in no BPMN
   * model. Nothing is configured for it, and its worker asks the cluster for the variable
   * because the method declares it.
   *
   * @param aggregate The workflow aggregate
   * @param bigPayload A variable no BPMN model mentions
   */
  @WorkflowTask
  public void fetchAllTask(
      final C8E2eAggregate aggregate,
      @TaskParam("bigPayload") final String bigPayload) {

    countInvocation("fetchAllTask", aggregate);
    OBSERVED_VARIABLES
        .put("bigPayloadLength", String.valueOf(bigPayload == null
            ? -1
            : bigPayload.length()));
    aggregate.appendResult("fetch-all");

  }

  /**
   * The default: nothing is configured for this task and
   * {@code bigPayload} appears in no BPMN model. Its worker still asks the cluster
   * for that variable, because the core reports the {@code @TaskParam} names of the
   * methods serving a task while the application wires itself.
   *
   * @param aggregate The workflow aggregate
   * @param bigPayload A variable no BPMN model mentions
   */
  @WorkflowTask
  public void fetchDerivedTask(
      final C8E2eAggregate aggregate,
      @TaskParam("bigPayload") final String bigPayload) {

    countInvocation("fetchDerivedTask", aggregate);
    OBSERVED_VARIABLES
        .put("derivedPayloadLength", String.valueOf(bigPayload == null
            ? -1
            : bigPayload.length()));
    aggregate.appendResult("fetch-derived");

  }

  @WorkflowTask
  public void collectPerItem(
      final C8E2eAggregate aggregate,
      @MultiInstanceElement("MI_FlatTask") final String item,
      @MultiInstanceIndex("MI_FlatTask") final int index,
      @MultiInstanceTotal("MI_FlatTask") final int total) {

    aggregate.setFlat(append(aggregate.getFlat(), "%s#%d/%d".formatted(item, index, total)));

  }

  @WorkflowTask
  public void collectNested(
      final C8E2eAggregate aggregate,
      @MultiInstanceElement("MI_OuterSub") final String group,
      @MultiInstanceIndex("MI_OuterSub") final int groupIndex,
      @MultiInstanceTotal("MI_OuterSub") final int groupTotal,
      @MultiInstanceElement("MI_NestedTask") final String item,
      @MultiInstanceIndex("MI_NestedTask") final int index,
      @MultiInstanceTotal("MI_NestedTask") final int total) {

    aggregate
        .setNested(
            append(
                aggregate.getNested(),
                "%s#%d/%d-%s#%d/%d".formatted(group, groupIndex, groupTotal, item, index, total)));

  }

  @WorkflowTask(taskDefinition = "recordSignal")
  public void recordSignal(
      final C8E2eAggregate aggregate) {

    countInvocation("recordSignal", aggregate);
    aggregate.appendResult("signal-received");

  }

  /**
   * The version of the deployed process definition decides which method serves the task.
   * This one is chosen by its number.
   *
   * @param aggregate The workflow aggregate
   */
  @WorkflowTask(taskDefinition = "versionedTask", version = "1")
  public void firstVersion(
      final C8E2eAggregate aggregate) {

    countInvocation("versionedTask", aggregate);
    aggregate.appendResult("firstVersion");

  }

  /**
   * This one is chosen by the <code>zeebe:versionTag</code> of the second version. That
   * version is deployed while the application runs, the way another node of a rolling
   * deployment does it. The adapter searches the cluster to learn which version carries
   * which tag. This is one of the reasons why the adapter needs a cluster it can search.
   * <p>
   * The range is open at the top, like the one of the Spring Boot test. A start refuses the
   * version it deploys if a task of it has no method for that version. Today this
   * application boots once, on a cluster of its own. But a later boot on the same cluster
   * would deploy the untagged model as a version after the tagged one, and exactly
   * 'release-2' would make that boot fail. Like the Spring Boot test, it also holds that a
   * range starting at a tag finds a version which came after the start.
   *
   * @param aggregate The workflow aggregate
   */
  @WorkflowTask(taskDefinition = "versionedTask", version = ">=release-2")
  public void taggedVersion(
      final C8E2eAggregate aggregate) {

    countInvocation("versionedTask", aggregate);
    aggregate.appendResult("taggedVersion");

  }

  /**
   * A plain task of a called process. It runs in the iteration of the subprocess which
   * holds the call activity. That subprocess belongs to another BPMN file. This engine says
   * nothing about it, so the adapter has to.
   */
  @WorkflowTask
  public void collectInCalledProcess(
      final C8E2eAggregate aggregate,
      @MultiInstanceElement("MIC_PerGroup") final String group,
      @MultiInstanceIndex("MIC_PerGroup") final int groupIndex,
      @MultiInstanceTotal("MIC_PerGroup") final int groupTotal) {

    aggregate
        .setInCalledProcess(
            append(aggregate.getInCalledProcess(), "%s#%d/%d".formatted(group, groupIndex, groupTotal)));

  }

  /**
   * A task which is multi-instance in the called process. It reports its own iteration and
   * the caller's. The resolver reports the order the two arrived in.
   */
  @WorkflowTask
  public void collectBothChains(
      final C8E2eAggregate aggregate,
      @MultiInstanceElement("MIC_PerGroup") final String group,
      @MultiInstanceIndex("MIC_PerGroup") final int groupIndex,
      @MultiInstanceTotal("MIC_PerGroup") final int groupTotal,
      @MultiInstanceElement("MIC_ChildMiTask") final String item,
      @MultiInstanceIndex("MIC_ChildMiTask") final int index,
      @MultiInstanceTotal("MIC_ChildMiTask") final int total,
      @MultiInstanceElement(resolverBean = MiCallChainResolver.class) final String chainOrder) {

    aggregate
        .setBothChains(
            append(
                aggregate.getBothChains(),
                "%s#%d/%d-%s#%d/%d".formatted(group, groupIndex, groupTotal, item, index, total)));
    aggregate.setChainOrder(chainOrder);

  }

  /**
   * A task two call activities away from the iteration it reports.
   */
  @WorkflowTask
  public void collectTwoLevelsDown(
      final C8E2eAggregate aggregate,
      @MultiInstanceElement("MIC_PerGroup") final String group,
      @MultiInstanceIndex("MIC_PerGroup") final int groupIndex,
      @MultiInstanceTotal("MIC_PerGroup") final int groupTotal) {

    aggregate
        .setTwoLevelsDown(
            append(aggregate.getTwoLevelsDown(), "%s#%d/%d".formatted(group, groupIndex, groupTotal)));

  }

  private static String append(
      final String current,
      final String value) {

    return current == null
        ? value
        : current
            + ","
            + value;

  }

}
