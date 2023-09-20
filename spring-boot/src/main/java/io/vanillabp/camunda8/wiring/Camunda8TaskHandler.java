package io.vanillabp.camunda8.wiring;

import io.camunda.zeebe.client.api.command.FinalCommandStep;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.client.api.worker.JobHandler;
import io.camunda.zeebe.spring.client.jobhandling.CommandWrapper;
import io.camunda.zeebe.spring.client.jobhandling.DefaultCommandExceptionHandlingStrategy;
import io.vanillabp.camunda8.wiring.Camunda8Connectable.Type;
import io.vanillabp.camunda8.wiring.parameters.Camunda8MultiInstanceIndexMethodParameter;
import io.vanillabp.camunda8.wiring.parameters.Camunda8MultiInstanceTotalMethodParameter;
import io.vanillabp.spi.service.TaskEvent.Event;
import io.vanillabp.spi.service.TaskException;
import io.vanillabp.springboot.adapter.MultiInstance;
import io.vanillabp.springboot.adapter.TaskHandlerBase;
import io.vanillabp.springboot.adapter.wiring.WorkflowAggregateCache;
import io.vanillabp.springboot.parameters.MethodParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.repository.CrudRepository;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

public class Camunda8TaskHandler extends TaskHandlerBase implements JobHandler {

    private static final Logger logger = LoggerFactory.getLogger(Camunda8TaskHandler.class);

    private final DefaultCommandExceptionHandlingStrategy commandExceptionHandlingStrategy;

    private final Type taskType;

    private final String idPropertyName;

    public Camunda8TaskHandler(
            final Type taskType,
            final DefaultCommandExceptionHandlingStrategy commandExceptionHandlingStrategy,
            final CrudRepository<Object, Object> workflowAggregateRepository,
            final Object bean,
            final Method method,
            final List<MethodParameter> parameters,
            final String idPropertyName) {

        super(workflowAggregateRepository, bean, method, parameters);
        this.taskType = taskType;
        this.commandExceptionHandlingStrategy = commandExceptionHandlingStrategy;
        this.idPropertyName = idPropertyName;

    }
    
    @Override
    protected Logger getLogger() {
        
        return logger;
        
    }

    @SuppressWarnings("unchecked")
    @Override
    @Transactional
    public void handle(
            final JobClient client,
            final ActivatedJob job) throws Exception {

        CommandWrapper command = null;
        try {
            final var businessKey = getVariable(job, idPropertyName);
            
            logger.trace("Will handle task '{}' of workflow '{}' ('{}') as job '{}'",
                    job.getElementId(),
                    job.getProcessInstanceKey(),
                    job.getProcessDefinitionKey(),
                    job.getKey());
            
            final var taskIdRetrieved = new AtomicBoolean(false);
            
            final Function<String, Object> multiInstanceSupplier
                    = multiInstanceVariable -> getVariable(job, multiInstanceVariable);
            
            final var workflowAggregateCache = new WorkflowAggregateCache();
            
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
                            () -> Event.CREATED),
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

            if ((taskType != Type.USERTASK)
                    && !taskIdRetrieved.get()) {
                command = createCompleteCommand(client, job, workflowAggregateCache.workflowAggregate);
            }
        } catch (TaskException bpmnError) {
            command = createThrowErrorCommand(client, job, bpmnError);
        } catch (Exception e) {
            logger.error("Failed to execute job '{}'", job.getKey(), e);
            command = createFailedCommand(client, job, e);
        }

        if (command != null) {
            command.executeAsync();
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
    public CommandWrapper createCompleteCommand(
            final JobClient jobClient,
            final ActivatedJob job,
            final Object workflowAggregateId) {

        var completeCommand = jobClient
                .newCompleteCommand(job.getKey());
        
        if (workflowAggregateId != null) {
            completeCommand = completeCommand.variables(workflowAggregateId);
        }
        
        return new CommandWrapper(
                (FinalCommandStep<Void>) ((FinalCommandStep<?>) completeCommand),
                job,
                commandExceptionHandlingStrategy);

    }

    private CommandWrapper createThrowErrorCommand(
            final JobClient jobClient,
            final ActivatedJob job,
            final TaskException bpmnError) {

        return new CommandWrapper(
                jobClient
                        .newThrowErrorCommand(job.getKey())
                        .errorCode(bpmnError.getErrorCode())
                        .errorMessage(bpmnError.getErrorName()),
                job,
                commandExceptionHandlingStrategy);

    }
    
    @SuppressWarnings("unchecked")
    private CommandWrapper createFailedCommand(
            final JobClient jobClient,
            final ActivatedJob job,
            final Exception e) {
        
        return new CommandWrapper(
                (FinalCommandStep<Void>) ((FinalCommandStep<?>) jobClient
                        .newFailCommand(job)
                        .retries(0)
                        .errorMessage(e.getMessage())),
                job,
                commandExceptionHandlingStrategy);
        
    }

}
