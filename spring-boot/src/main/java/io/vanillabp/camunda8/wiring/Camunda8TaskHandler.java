package io.vanillabp.camunda8.wiring;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.search.enums.JobKind;
import io.camunda.client.api.search.enums.ListenerEventType;
import io.camunda.client.api.worker.JobClient;
import io.camunda.client.api.worker.JobHandler;
import io.vanillabp.camunda8.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.LoggingContext;
import io.vanillabp.camunda8.service.Camunda8TransactionAspect;
import io.vanillabp.camunda8.service.Camunda8TransactionProcessor;
import io.vanillabp.camunda8.wiring.Camunda8Connectable.Type;
import io.vanillabp.camunda8.wiring.parameters.Camunda8MultiInstanceIndexMethodParameter;
import io.vanillabp.camunda8.wiring.parameters.Camunda8MultiInstanceTotalMethodParameter;
import io.vanillabp.spi.service.MultiInstanceElementResolver;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskException;
import io.vanillabp.springboot.adapter.MultiInstance;
import io.vanillabp.springboot.adapter.TaskHandlerBase;
import io.vanillabp.springboot.adapter.wiring.WorkflowAggregateCache;
import io.vanillabp.springboot.parameters.MethodParameter;
import io.vanillabp.springboot.parameters.ResolverBasedMultiInstanceMethodParameter;
import io.vanillabp.springboot.parameters.WorkflowAggregateMethodParameter;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.repository.CrudRepository;
import org.springframework.util.StringUtils;

public class Camunda8TaskHandler extends TaskHandlerBase implements JobHandler {

    public static final String BPMN_ERROR_VARIABLE = "io.vanillabp:bpmnError";

    private static final Logger logger = LoggerFactory.getLogger(Camunda8TaskHandler.class);

    private final Type taskType;

    private final String idPropertyName;

    private final String tenantId;

    private final String workflowModuleId;

    private final String bpmnProcessId;

    private final boolean publishUserTaskIdAsHexString;

    private final CamundaClient camundaClient;

    public Camunda8TaskHandler(
            final Type taskType,
            final CrudRepository<Object, Object> workflowAggregateRepository,
            final Object bean,
            final Method method,
            final List<MethodParameter> parameters,
            final String idPropertyName,
            final String tenantId,
            final String workflowModuleId,
            final String bpmnProcessId,
            final boolean publishUserTaskIdAsHexString,
            final CamundaClient camundaClient) {

        super(workflowAggregateRepository, bean, method, parameters);
        this.taskType = taskType;
        this.idPropertyName = idPropertyName;
        this.tenantId = tenantId;
        this.workflowModuleId = workflowModuleId;
        this.bpmnProcessId = bpmnProcessId;
        this.publishUserTaskIdAsHexString = publishUserTaskIdAsHexString;
        this.camundaClient = camundaClient;

    }

    @Override
    protected Logger getLogger() {

        return logger;

    }

    private void handleUserTaskBpmnError(
            final JobClient client,
            final ActivatedJob job) {

        final var bpmnError = job.getVariablesAsMap().get(BPMN_ERROR_VARIABLE);
        // if user task isn't completed by BPMN error?
        if ((bpmnError == null) || !StringUtils.hasText(bpmnError.toString())) {
            client
                    .newCompleteCommand(job)
                    .send()
                    .join();
            return;
        }

        client
                .newThrowErrorCommand(job)
                .errorCode(bpmnError.toString())
                //.variable(BPMN_ERROR_VARIABLE, null) // not allowed; instead it has to be unset on every successful user task completion
                .send()
                .join();

    }

    @SuppressWarnings("unchecked")
    @Override
    public void handle(
            final JobClient client,
            final ActivatedJob job) throws Exception {

        if ((taskType == Type.USERTASK_ZEEBE) // BPMN-Error processing currently not working
                && (job.getKind() == JobKind.EXECUTION_LISTENER)
                && (job.getListenerEventType() == ListenerEventType.END)) {
                //&& (job.getKind() == JobKind.TASK_LISTENER)
                //&& (job.getListenerEventType() == ListenerEventType.COMPLETING)) {
            handleUserTaskBpmnError(client, job);
            return;
        }

        try {
            final var isListener = (job.getKind() == JobKind.TASK_LISTENER);
                    // || (job.getKind() == JobKind.EXECUTION_LISTENER);   // BPMN-Error processing currently not working
            final var eventType = job.getKind() == JobKind.TASK_LISTENER
                    ? job.getListenerEventType() == ListenerEventType.CANCELING
                            ? TaskEvent.Event.CANCELED
                            : TaskEvent.Event.CREATED
                    : TaskEvent.Event.CREATED;
            final var taskKey = job.getUserTask() != null
                    ? job.getUserTask().getUserTaskKey()
                    : job.getKey();
            final var businessKey = getVariable(job, idPropertyName);
            final var taskId = publishUserTaskIdAsHexString
                    ? Long.toHexString(taskKey)
                    : Long.toString(taskKey);

            LoggingContext.setLoggingContext(
                    Camunda8AdapterConfiguration.ADAPTER_ID,
                    tenantId,
                    workflowModuleId,
                    businessKey == null ? null : businessKey.toString(),
                    bpmnProcessId,
                    taskId,
                    Long.toString(job.getProcessInstanceKey()),
                    job.getBpmnProcessId() + "#" + job.getElementId(),
                    Long.toString(job.getElementInstanceKey()));

            logger.trace("Will handle task-event {} for '{}' (task-definition '{}‘) of workflow '{}' (instance-id '{}') as job '{}' (for task '{}')",
                    eventType,
                    job.getElementId(),
                    job.getType(),
                    job.getBpmnProcessId(),
                    job.getProcessInstanceKey(),
                    job.getKey(),
                    taskKey);

            final var taskIdRetrieved = new AtomicBoolean(false);
            final var workflowAggregateCache = new WorkflowAggregateCache();

            Camunda8TransactionAspect.registerDeferredInTransaction(
                    new Camunda8TransactionAspect.RunDeferredInTransactionSupplier[parameters.size()],
                    saveAggregateAfterWorkflowTask(workflowAggregateCache));

            // Any callback used in this method is executed in case of no active transaction.
            // In case of an active transaction the callbacks are used by the Camunda8TransactionInterceptor.
            Camunda8TransactionProcessor.registerCallbacks(
                    () -> {
                        if (taskType == Type.USERTASK) { // user tasks are always async
                            return null;
                        }
                        if (!isListener && (taskType != Type.USERTASK_ZEEBE)) { // zeebe user tasks and listeners are always auto-completed
                            if (taskIdRetrieved.get()) { // async processing of service-task
                                return null;
                            }
                        }
                        return testForTaskWasCompletedOrCancelled(job);
                    },
                    doThrowError(client, job, workflowAggregateCache),
                    doFailed(client, job),
                    () -> {
                        if (taskType == Type.USERTASK) { // user tasks are always async
                            return null;
                        }
                        if (!isListener && (taskType != Type.USERTASK_ZEEBE)) { // zeebe user tasks and listeners are always auto-completed
                            if (taskIdRetrieved.get()) { // async processing of service-task
                                return null;
                            }
                        }
                        return doComplete(
                                client,
                                job,
                                // no updates of variables allowed for listeners
                                isListener ? null : workflowAggregateCache);
                    });

            final Function<String, Object> multiInstanceSupplier
                    = multiInstanceVariable -> getVariable(job, multiInstanceVariable);

            super.execute(
                    workflowAggregateCache,
                    businessKey,
                    false, // will be done within transaction boundaries
                    (args, param) -> processTaskParameter(
                            args,
                            param,
                            taskParameter -> getVariable(job, taskParameter)),
                    (args, param) -> processTaskIdParameter(
                            args,
                            param,
                            () -> {
                                taskIdRetrieved.set(true);
                                return taskId;
                            }),
                    (args, param) -> processTaskEventParameter(
                            args,
                            param,
                            () -> eventType),
                    (args, param) -> processMultiInstanceIndexParameter(
                            args,
                            param,
                            multiInstanceSupplier),
                    (args, param) -> processMultiInstanceTotalParameter(
                            args,
                            param,
                            multiInstanceSupplier),
                    (args, param) -> processMultiInstanceElementParameter(
                            args,
                            param,
                            multiInstanceSupplier),
                    (args, param) -> processMultiInstanceResolverParameter(
                            args,
                            param,
                            () -> {
                                if (workflowAggregateCache.workflowAggregate == null) {
                                    workflowAggregateCache.workflowAggregate = workflowAggregateRepository
                                            .findById(businessKey)
                                            .orElseThrow();
                                }
                                return workflowAggregateCache.workflowAggregate;
                            }, multiInstanceSupplier));

        } finally {
            Camunda8TransactionProcessor.unregisterCallbacks();
            Camunda8TransactionAspect.unregisterDeferredInTransaction();
            LoggingContext.clearContext();
        }

    }

    @Override
    protected Object getMultiInstanceElement(
            final String name,
            final Function<String, Object> multiInstanceSupplier) {

        return multiInstanceSupplier
                .apply(name);

    }

    @Override
    protected Integer getMultiInstanceIndex(
            final String name,
            final Function<String, Object> multiInstanceSupplier) {

        return (Integer) multiInstanceSupplier
                .apply(name + Camunda8MultiInstanceIndexMethodParameter.SUFFIX) - 1;

    }

    @Override
    protected Integer getMultiInstanceTotal(
            final String name,
            final Function<String, Object> multiInstanceSupplier) {

        return (Integer) multiInstanceSupplier
                .apply(name + Camunda8MultiInstanceTotalMethodParameter.SUFFIX);

    }

    @Override
    protected MultiInstance<Object> getMultiInstance(
            final String name,
            final Function<String, Object> multiInstanceSupplier) {

        return new MultiInstance<Object>(
                getMultiInstanceElement(name, multiInstanceSupplier),
                getMultiInstanceTotal(name, multiInstanceSupplier),
                getMultiInstanceIndex(name, multiInstanceSupplier));

    }

    private Object getVariable(
            final ActivatedJob job,
            final String name) {

        return job
                .getVariablesAsMap()
                .get(name);

    }

    public Runnable saveAggregateAfterWorkflowTask(
            final WorkflowAggregateCache aggregateCache) {

        return () -> {
                if (aggregateCache.workflowAggregate != null) {
                    workflowAggregateRepository.save(aggregateCache.workflowAggregate);
                }
            };

    }

        @SuppressWarnings("unchecked")
    public Camunda8TransactionAspect.CommandWithFallback testForTaskWasCompletedOrCancelled(
            final ActivatedJob job) {

        final var result = new Camunda8TransactionAspect.CommandWithFallback();
        result.command = () -> camundaClient
                .newUpdateTimeoutCommand(job)
                .timeout(Duration.ofMinutes(10))
                .send()
                .join(5, TimeUnit.MINUTES);
        result.descriptor = () -> "update timeout (BPMN: " + job.getBpmnProcessId()
                + "; Element: " + job.getElementId()
                + "; Task-Definition: " + job.getType()
                + "; Process-Instance: " + job.getProcessInstanceKey()
                + "; Job: " + job.getKey()
                + ")";
        return result;

    }

    @SuppressWarnings("unchecked")
    public Camunda8TransactionAspect.CommandWithFallback doComplete(
            final JobClient jobClient,
            final ActivatedJob job,
            final WorkflowAggregateCache workflowAggregateCache) {

        final var result = new Camunda8TransactionAspect.CommandWithFallback();
        result.command = () -> {
                var completeCommand = jobClient
                        .newCompleteCommand(job.getKey());

                if ((workflowAggregateCache != null)
                        && (workflowAggregateCache.workflowAggregate != null)) {
                    completeCommand = completeCommand.variables(workflowAggregateCache.workflowAggregate);
                }

                completeCommand
                        .send()
                        .exceptionally(t -> {
                            throw new RuntimeException("error", t);
                        });
            };
        result.descriptor = () -> "complete command (BPMN: " + job.getBpmnProcessId()
                + "; Element: " + job.getElementId()
                + "; Task-Definition: " + job.getType()
                + "; Process-Instance: " + job.getProcessInstanceKey()
                + "; Job: " + job.getKey()
                + ")";
        return result;

    }

    private Map.Entry<Consumer<TaskException>, Function<TaskException, String>> doThrowError(
            final JobClient jobClient,
            final ActivatedJob job,
            final WorkflowAggregateCache workflowAggregateCache) {

        return Map.entry(
                taskException -> {
                    var throwErrorCommand = jobClient
                            .newThrowErrorCommand(job.getKey())
                            .errorCode(taskException.getErrorCode())
                            .errorMessage(taskException.getErrorName());

                    if (workflowAggregateCache.workflowAggregate != null) {
                        throwErrorCommand = throwErrorCommand.variables(workflowAggregateCache.workflowAggregate);
                    }

                    throwErrorCommand
                            .send()
                            .exceptionally(t -> {
                                throw new RuntimeException("error", t);
                            });
                },
                taskException -> "throw error command (BPMN: " + job.getBpmnProcessId()
                        + "; Element: " + job.getElementId()
                        + "; Task-Definition: " + job.getType()
                        + "; Process-Instance: " + job.getProcessInstanceKey()
                        + "; Job: " + job.getKey()
                        + ")");
    }

    @SuppressWarnings("unchecked")
    private Map.Entry<Consumer<Exception>, Function<Exception, String>> doFailed(
            final JobClient jobClient,
            final ActivatedJob job) {

        return Map.entry(
                exception -> {
                    jobClient
                            .newFailCommand(job)
                            .retries(0)
                            .errorMessage(exception.getMessage())
                            .send()
                            .exceptionally(t -> {
                                throw new RuntimeException("error", t);
                            });
                },
                taskException -> "fail command (BPMN: " + job.getBpmnProcessId()
                        + "; Element: " + job.getElementId()
                        + "; Task-Definition: " + job.getType()
                        + "; Process-Instance: " + job.getProcessInstanceKey()
                        + "; Job: " + job.getKey()
                        + ")");

    }

    protected boolean processWorkflowAggregateParameter(
            final Object[] args,
            final MethodParameter param,
            final WorkflowAggregateCache workflowAggregateCache,
            final Object workflowAggregateId) {

        if (!(param instanceof WorkflowAggregateMethodParameter)) {
            return true;
        }

        Camunda8TransactionAspect.runDeferredInTransaction.get().argsSupplier[param.getIndex()] = () -> {
            // Using findById is required to get an object instead of a Hibernate proxy.
            // Otherwise, for e.g. Camunda8 connector JSON serialization of the
            // workflow aggregate is not possible.
            workflowAggregateCache.workflowAggregate = workflowAggregateRepository
                    .findById(workflowAggregateId)
                    .orElse(null);
            return workflowAggregateCache.workflowAggregate;
        };

        args[param.getIndex()] = null; // will be set by deferred execution of supplier

        return false;

    }

    protected boolean processMultiInstanceResolverParameter(
            final Object[] args,
            final MethodParameter param,
            final Supplier<Object> workflowAggregate,
            final Function<String, Object> multiInstanceSupplier) {

        if (!(param instanceof ResolverBasedMultiInstanceMethodParameter)) {
            return true;
        }

        @SuppressWarnings("unchecked")
        final var resolver =
                (MultiInstanceElementResolver<Object, Object>)
                        ((ResolverBasedMultiInstanceMethodParameter) param).getResolverBean();

        final var multiInstances = new HashMap<String, MultiInstanceElementResolver.MultiInstance<Object>>();

        resolver
                .getNames()
                .forEach(name -> multiInstances.put(name, getMultiInstance(name, multiInstanceSupplier)));

        Camunda8TransactionAspect.runDeferredInTransaction.get().argsSupplier[param.getIndex()] =  () -> {
                try {
                    return resolver.resolve(workflowAggregate.get(), multiInstances);
                } catch (Exception e) {
                    throw new RuntimeException(
                            "Failed processing MultiInstanceElementResolver for parameter '"
                                    + param.getParameter()
                                    + "' of method '"
                                    + method
                                    + "'", e);
                }
            };

        args[param.getIndex()] = null; // will be set by deferred execution of supplier

        return false;

    }

}
