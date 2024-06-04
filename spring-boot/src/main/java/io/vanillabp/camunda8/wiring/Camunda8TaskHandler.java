package io.vanillabp.camunda8.wiring;

import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.client.api.worker.JobHandler;
import io.vanillabp.camunda8.service.Camunda8TransactionProcessor;
import io.vanillabp.camunda8.wiring.Camunda8Connectable.Type;
import io.vanillabp.camunda8.wiring.parameters.Camunda8MultiInstanceIndexMethodParameter;
import io.vanillabp.camunda8.wiring.parameters.Camunda8MultiInstanceTotalMethodParameter;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskException;
import io.vanillabp.springboot.adapter.MultiInstance;
import io.vanillabp.springboot.adapter.TaskHandlerBase;
import io.vanillabp.springboot.adapter.wiring.WorkflowAggregateCache;
import io.vanillabp.springboot.parameters.MethodParameter;
import java.lang.reflect.Method;
import java.time.Duration;
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

public class Camunda8TaskHandler extends TaskHandlerBase implements JobHandler, Consumer<ZeebeClient> {

    private static final Logger logger = LoggerFactory.getLogger(Camunda8TaskHandler.class);

    private final Type taskType;

    private final String idPropertyName;

    private ZeebeClient zeebeClient;

    public Camunda8TaskHandler(
            final Type taskType,
            final CrudRepository<Object, Object> workflowAggregateRepository,
            final Object bean,
            final Method method,
            final List<MethodParameter> parameters,
            final String idPropertyName) {

        super(workflowAggregateRepository, bean, method, parameters);
        this.taskType = taskType;
        this.idPropertyName = idPropertyName;

    }

    @Override
    public void accept(
            final ZeebeClient zeebeClient) {

        this.zeebeClient = zeebeClient;

    }

    @Override
    protected Logger getLogger() {
        
        return logger;
        
    }

    @SuppressWarnings("unchecked")
    @Override
    public void handle(
            final JobClient client,
            final ActivatedJob job) {

        Runnable jobPostAction = null;
        Supplier<String> description = null;
        try {
            final var businessKey = getVariable(job, idPropertyName);

            logger.trace("Will handle task '{}' (task-definition '{}‘) of workflow '{}' (instance-id '{}') as job '{}'",
                    job.getElementId(),
                    job.getType(),
                    job.getBpmnProcessId(),
                    job.getProcessInstanceKey(),
                    job.getKey());

            final var taskIdRetrieved = new AtomicBoolean(false);
            final var workflowAggregateCache = new WorkflowAggregateCache();

            // Any callback used in this method is executed in case of no active transaction.
            // In case of an active transaction the callbacks are used by the Camunda8TransactionInterceptor.
            Camunda8TransactionProcessor.registerCallbacks(
                    () -> {
                        if (taskType == Type.USERTASK) {
                            return null;
                        }
                        if (taskIdRetrieved.get()) { // async processing of service-task
                            return null;
                        }
                        return doTestForTaskWasCompletedOrCancelled(job);
                    },
                    doThrowError(client, job, workflowAggregateCache),
                    doFailed(client, job),
                    () -> {
                        if (taskType == Type.USERTASK) {
                            return null;
                        }
                        if (taskIdRetrieved.get()) { // async processing of service-task
                            return null;
                        }
                        return doComplete(client, job, workflowAggregateCache);
                    });

            final Function<String, Object> multiInstanceSupplier
                    = multiInstanceVariable -> getVariable(job, multiInstanceVariable);

            super.execute(
                    workflowAggregateCache,
                    businessKey,
                    true,
                    (args, param) -> processTaskParameter(
                            args,
                            param,
                            taskParameter -> getVariable(job, taskParameter)),
                    (args, param) -> processTaskIdParameter(
                            args,
                            param,
                            () -> {
                                taskIdRetrieved.set(true);
                                return Long.toHexString(job.getKey());
                            }),
                    (args, param) -> processTaskEventParameter(
                            args,
                            param,
                            () -> TaskEvent.Event.CREATED),
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

            final var callback = Camunda8TransactionProcessor.handlerCompletedCommandCallback();
            if (callback != null) {
                jobPostAction = callback.getKey();
                description = callback.getValue();
            }
        } catch (TaskException bpmnError) {
            final var callback = Camunda8TransactionProcessor.bpmnErrorCommandCallback();
            if (callback != null) {
                jobPostAction = () -> callback.getKey().accept(bpmnError);
                description = () -> callback.getValue().apply(bpmnError);
            }
        } catch (Exception e) {
            final var callback = Camunda8TransactionProcessor.handlerFailedCommandCallback();
            if (callback != null) {
                logger.error("Failed to execute job '{}'", job.getKey(), e);
                jobPostAction = () -> callback.getKey().accept(e);
                description = () -> callback.getValue().apply(e);
            }
        } finally {
            Camunda8TransactionProcessor.unregisterCallbacks();
        }

        if (jobPostAction != null) {
            try {
                jobPostAction.run();
            } catch (Exception e) {
                if (description != null) {
                    logger.error(
                            "Could not execute '{}'! Manual action required!",
                            description.get(),
                            e);
                } else {
                    logger.error(
                            "Manual action required due to:",
                            e);
                }
            }
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

    @SuppressWarnings("unchecked")
    public Map.Entry<Runnable, Supplier<String>> doTestForTaskWasCompletedOrCancelled(
            final ActivatedJob job) {

        return Map.entry(
                () -> zeebeClient
                        .newUpdateTimeoutCommand(job)
                        .timeout(Duration.ofMinutes(10))
                        .send()
                        .join(5, TimeUnit.MINUTES), // needs to run synchronously
                () -> "update timeout (BPMN: " + job.getBpmnProcessId()
                        + "; Element: " + job.getElementId()
                        + "; Task-Definition: " + job.getType()
                        + "; Process-Instance: " + job.getProcessInstanceKey()
                        + "; Job: " + job.getKey()
                        + ")");

    }

    @SuppressWarnings("unchecked")
    public Map.Entry<Runnable, Supplier<String>> doComplete(
            final JobClient jobClient,
            final ActivatedJob job,
            final WorkflowAggregateCache workflowAggregateCache) {

        return Map.entry(
                () -> {
                    var completeCommand = jobClient
                            .newCompleteCommand(job.getKey());

                    if (workflowAggregateCache.workflowAggregate != null) {
                        completeCommand = completeCommand.variables(workflowAggregateCache.workflowAggregate);
                    }

                    completeCommand
                            .send()
                            .exceptionally(t -> {
                                throw new RuntimeException("error", t);
                            });
                },
                () -> "complete command (BPMN: " + job.getBpmnProcessId()
                        + "; Element: " + job.getElementId()
                        + "; Task-Definition: " + job.getType()
                        + "; Process-Instance: " + job.getProcessInstanceKey()
                        + "; Job: " + job.getKey()
                        + ")");

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
                            .exceptionally(t -> { throw new RuntimeException("error", t); });
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

}
