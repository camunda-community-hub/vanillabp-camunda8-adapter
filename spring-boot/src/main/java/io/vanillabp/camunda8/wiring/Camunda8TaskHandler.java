package io.vanillabp.camunda8.wiring;

import io.camunda.zeebe.client.api.command.FinalCommandStep;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.client.api.worker.JobHandler;
import io.camunda.zeebe.model.bpmn.instance.Activity;
import io.camunda.zeebe.model.bpmn.instance.BaseElement;
import io.camunda.zeebe.model.bpmn.instance.MultiInstanceLoopCharacteristics;
import io.camunda.zeebe.spring.client.jobhandling.CommandWrapper;
import io.camunda.zeebe.spring.client.jobhandling.DefaultCommandExceptionHandlingStrategy;
import io.vanillabp.camunda8.deployment.DeploymentService;
import io.vanillabp.camunda8.wiring.Camunda8Connectable.Type;
import io.vanillabp.camunda8.wiring.parameters.Camunda8MultiInstanceIndexMethodParameter;
import io.vanillabp.camunda8.wiring.parameters.Camunda8MultiInstanceTotalMethodParameter;
import io.vanillabp.spi.service.MultiInstanceElementResolver;
import io.vanillabp.spi.service.TaskEvent.Event;
import io.vanillabp.spi.service.TaskException;
import io.vanillabp.springboot.adapter.MultiInstance;
import io.vanillabp.springboot.adapter.TaskHandlerBase;
import io.vanillabp.springboot.parameters.MethodParameter;
import org.camunda.bpm.model.xml.ModelInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.repository.CrudRepository;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

public class Camunda8TaskHandler extends TaskHandlerBase implements JobHandler {

    private static final Logger logger = LoggerFactory.getLogger(Camunda8TaskHandler.class);

    private final DefaultCommandExceptionHandlingStrategy commandExceptionHandlingStrategy;

    private final Type taskType;

    private final DeploymentService deploymentService;

    private final String idPropertyName;

    public Camunda8TaskHandler(
            final Type taskType,
            final DeploymentService deploymentService,
            final DefaultCommandExceptionHandlingStrategy commandExceptionHandlingStrategy,
            final CrudRepository<Object, String> workflowAggregateRepository,
            final Object bean,
            final Method method,
            final List<MethodParameter> parameters,
            final String idPropertyName) {

        super(workflowAggregateRepository, bean, method, parameters);
        this.deploymentService = deploymentService;
        this.taskType = taskType;
        this.commandExceptionHandlingStrategy = commandExceptionHandlingStrategy;
        this.idPropertyName = idPropertyName;

    }
    
    @Override
    protected Logger getLogger() {
        
        return logger;
        
    }

    @Override
    @Transactional
    public void handle(
            final JobClient client,
            final ActivatedJob job) throws Exception {

        CommandWrapper command = null;
        try {
            final var businessKey = (String) getVariable(job, idPropertyName);
            
            logger.trace("Will handle task '{}' of workflow '{}' ('{}') as job '{}'",
                    job.getElementId(),
                    job.getProcessInstanceKey(),
                    job.getProcessDefinitionKey(),
                    job.getKey());
            
            final var taskIdRetrieved = new AtomicBoolean(false);
            
            final var workflowAggregate = super.execute(
                    businessKey,
                    multiInstanceVariable -> getVariable(job, multiInstanceVariable),
                    taskParameter -> getVariable(job, taskParameter),
                    () -> {
                        taskIdRetrieved.set(true);
                        return Long.toHexString(job.getKey());
                    },
                    () -> Event.CREATED);

            if ((taskType != Type.USERTASK)
                    && !taskIdRetrieved.get()) {
                command = createCompleteCommand(client, job, workflowAggregate);
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

    /**
     * @deprecated Attempt to calculate variables but it's not possible
     */
    @Deprecated
    protected Map<String, MultiInstanceElementResolver.MultiInstance<Object>> getMultiInstanceContext(
            final ActivatedJob job,
            final String workflowAggregateId) {

        final var result = new LinkedHashMap<String, MultiInstanceElementResolver.MultiInstance<Object>>();

        final var process = deploymentService
                .getProcess(job.getProcessDefinitionKey());

        ModelInstance model = process.getModelInstance();
        String miElement = job.getElementId();
        MultiInstanceLoopCharacteristics loopCharacteristics = null;
        // find multi-instance element from current element up to the root of the
        // process-hierarchy
        while (loopCharacteristics == null) {
            
            // check current element for multi-instance
            final var bpmnElement = model.getModelElementById(miElement);
            if (bpmnElement instanceof Activity) {
                loopCharacteristics = (MultiInstanceLoopCharacteristics) ((Activity) bpmnElement)
                        .getLoopCharacteristics();
            }
            
            // if still not found then check parent
            if (loopCharacteristics == null) {
                miElement = bpmnElement.getParentElement() != null
                        ? ((BaseElement) bpmnElement.getParentElement()).getId()
                        : null;
            }
            // multi-instance found
            else {
                
                result.put(((BaseElement) bpmnElement).getId(),
                        new MultiInstance<Object>(null, -1, -1));
                
            }
            
            // if there is no parent then multi-instance task was used in a
            // non-multi-instance environment
            if ((miElement == null) && (loopCharacteristics == null)) {
                throw new RuntimeException(
                        "No multi-instance context found for element '"
                        + job.getElementId()
                                + "' or its parents! In case of a call-activity this is not supported by ");
            }
            
        }
        
        return result;

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
