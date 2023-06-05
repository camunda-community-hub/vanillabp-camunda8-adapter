package io.vanillabp.camunda8.wiring;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.persistence.Id;

import org.camunda.bpm.model.xml.instance.ModelElementInstance;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;

import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.worker.JobWorkerBuilderStep1.JobWorkerBuilderStep3;
import io.camunda.zeebe.model.bpmn.impl.BpmnModelInstanceImpl;
import io.camunda.zeebe.model.bpmn.instance.BaseElement;
import io.camunda.zeebe.model.bpmn.instance.Process;
import io.camunda.zeebe.model.bpmn.instance.UserTask;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeFormDefinition;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeLoopCharacteristics;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskDefinition;
import io.vanillabp.camunda8.deployment.Camunda8DeploymentAdapter;
import io.vanillabp.camunda8.service.Camunda8ProcessService;
import io.vanillabp.camunda8.wiring.Camunda8Connectable.Type;
import io.vanillabp.camunda8.wiring.parameters.Camunda8MethodParameterFactory;
import io.vanillabp.camunda8.wiring.parameters.ParameterVariables;
import io.vanillabp.spi.service.WorkflowTask;
import io.vanillabp.springboot.adapter.SpringDataUtil;
import io.vanillabp.springboot.adapter.TaskWiringBase;
import io.vanillabp.springboot.parameters.MethodParameter;

public class Camunda8TaskWiring extends TaskWiringBase<Camunda8Connectable, Camunda8ProcessService<?>>
        implements Consumer<ZeebeClient> {

    private final String workerId;
    
    private final SpringDataUtil springDataUtil;
    
    private final ObjectProvider<Camunda8TaskHandler> taskHandlers;

    private final Collection<Camunda8ProcessService<?>> connectableServices;
    
    private final Camunda8UserTaskHandler userTaskHandler;

    private ZeebeClient client;
    
    private List<JobWorkerBuilderStep3> workers = new LinkedList<>();
    
    public Camunda8TaskWiring(
            final SpringDataUtil springDataUtil,
            final ApplicationContext applicationContext,
            final String workerId,
            final Camunda8UserTaskHandler userTaskHandler,
            final ObjectProvider<Camunda8TaskHandler> taskHandlers,
            final Collection<Camunda8ProcessService<?>> connectableServices) {
        
        super(applicationContext, new Camunda8MethodParameterFactory());
        this.workerId = workerId;
        this.springDataUtil = springDataUtil;
        this.taskHandlers = taskHandlers;
        this.userTaskHandler = userTaskHandler;
        this.connectableServices = connectableServices;
        
    }
    
    @Override
    protected Class<WorkflowTask> getAnnotationType() {
        
        return WorkflowTask.class;
        
    }
    
    /**
     * Called by
     * {@link Camunda8DeploymentAdapter#processBpmnModel(BpmnModelInstanceImpl)} to
     * ensure client is available before using wire-methods.
     */
    @Override
    public void accept(
            final ZeebeClient client) {
        
        this.client = client;
        
        // fetch all usertasks spawned
        workers.add(
                client
                        .newWorker()
                        .jobType("io.camunda.zeebe:userTask")
                        .handler(userTaskHandler)
                        .timeout(Integer.MAX_VALUE) // user-tasks are not fetched more than once
                        .name(workerId));
        
    }
    
    public void openWorkers() {
        
        workers
                .stream()
                .forEach(JobWorkerBuilderStep3::open);
        
    }

    public Stream<Camunda8Connectable> connectablesForType(
            final Process process,
            final BpmnModelInstanceImpl model,
            final Class<? extends BaseElement> type) {
        
        final var kind = UserTask.class.isAssignableFrom(type) ? Type.USERTASK : Type.TASK;
        
        final var stream = model
                .getModelElementsByType(type)
                .stream()
                .filter(element -> getOwningProcess(element).equals(process))
                .map(element -> new Camunda8Connectable(
                        process,
                        element.getId(),
                        kind,
                        getTaskDefinition(kind, element),
                        element.getSingleExtensionElement(ZeebeLoopCharacteristics.class)))
                .filter(connectable -> connectable.isExecutableProcess());
        
        if (kind == Type.USERTASK) {
            return stream;
        }
        
        return stream.filter(connectable -> connectable.getTaskDefinition() != null);
        
    }
    
    private String getTaskDefinition(
            final Type kind,
            final BaseElement element) {
        
        if (kind == Type.USERTASK) {
            
            final var formDefinition = element.getSingleExtensionElement(ZeebeFormDefinition.class);
            if (formDefinition == null) {
                return null;
            }
            return formDefinition.getFormKey();
            
        }
        
        final var taskDefinition = element.getSingleExtensionElement(ZeebeTaskDefinition.class);
        if (taskDefinition == null) {
            return null;
        }
        return taskDefinition.getType();
        
    }
    
    static Process getOwningProcess(
            final ModelElementInstance element) {

        if (element instanceof Process) {
            return (Process) element;
        }

        final var parent = element.getParentElement();
        if (parent == null) {
            return null;
        }

        return getOwningProcess(parent);

    }
    
    @Override
    protected <DE> Camunda8ProcessService<?> connectToBpms(
            final String workflowModuleId,
            final Class<DE> workflowAggregateClass,
            final String bpmnProcessId,
            final boolean isPrimary,
            final Collection<String> messageBasedStartEventsMessageNames,
            final Collection<String> signalBasedStartEventsSignalNames) {
        
        final var processService = connectableServices
                .stream()
                .filter(service -> service.getWorkflowAggregateClass().equals(workflowAggregateClass))
                .findFirst()
                .get();

        processService.wire(
                client,
                workflowModuleId,
                bpmnProcessId,
                isPrimary,
                messageBasedStartEventsMessageNames,
                signalBasedStartEventsSignalNames);

        return processService;
        
    }
    
    @Override
    protected void connectToBpms(
            final Camunda8ProcessService<?> processService,
            final Object bean,
            final Camunda8Connectable connectable,
            final Method method,
            final List<MethodParameter> parameters) {
        
        final var repository = processService.getWorkflowAggregateRepository();
        final var idPropertyName = getWorkflowAggregateIdPropertyName(
                processService.getWorkflowAggregateClass());

        final var taskHandler = taskHandlers.getObject(
                springDataUtil,
                repository,
                connectable.getType(),
                connectable.getTaskDefinition(),
                bean,
                method,
                parameters,
                idPropertyName);

        if (connectable.getType() == Type.USERTASK) {
            
            userTaskHandler.addTaskHandler(
                    connectable.getBpmnProcessId(),
                    connectable.getElementId(),
                    taskHandler);
            return;
            
        }
        
        final var variablesToFetch = getVariablesToFetch(idPropertyName, parameters);

        workers.add(
                client
                        .newWorker()
                        .jobType(connectable.getTaskDefinition())
                        .handler(taskHandler)
                        .name(workerId)
                        .fetchVariables(variablesToFetch));

              // using defaults from config if null, 0 or negative
//              if (zeebeWorkerValue.getName() != null && zeebeWorkerValue.getName().length() > 0) {
//                builder.name(zeebeWorkerValue.getName());
//              } else {
//                builder.name(beanInfo.getBeanName() + "#" + zeebeWorkerValue.getMethodInfo().getMethodName());
//              }
//              if (zeebeWorkerValue.getMaxJobsActive() > 0) {
//                builder.maxJobsActive(zeebeWorkerValue.getMaxJobsActive());
//              }
//              if (zeebeWorkerValue.getTimeout() > 0) {
//                builder.timeout(zeebeWorkerValue.getTimeout());
//              }
//              if (zeebeWorkerValue.getPollInterval() > 0) {
//                builder.pollInterval(Duration.ofMillis(zeebeWorkerValue.getPollInterval()));
//              }
//              if (zeebeWorkerValue.getRequestTimeout() > 0) {
//                builder.requestTimeout(Duration.ofSeconds(zeebeWorkerValue.getRequestTimeout()));
//              }
//              if (zeebeWorkerValue.getFetchVariables().length > 0) {
//                builder.fetchVariables(zeebeWorkerValue.getFetchVariables());
//              }
        
    }

    private String getWorkflowAggregateIdPropertyName(
            final Class<?> workflowAggregateClass) {
        
        if (workflowAggregateClass == null) {
            return null;
        }
        
        return Arrays
                .stream(workflowAggregateClass.getDeclaredFields())
                .filter(field -> field.getAnnotation(Id.class) != null)
                .findFirst()
                .map(field -> field.getName())
                .orElse(Arrays
                        .stream(workflowAggregateClass.getDeclaredMethods())
                        .filter(method -> method.getAnnotation(Id.class) != null)
                        .findFirst()
                        .map(this::propertyName)
                        .orElse(getWorkflowAggregateIdPropertyName(workflowAggregateClass.getSuperclass())));
        
    }
    
    private String propertyName(
            final Method method) {
        
        if (method.getName().startsWith("get")) {
            if (method.getName().length() < 4) {
                return method.getName();
            }
            
            return
                    method.getName().substring(3, 4).toLowerCase()
                    + method.getName().substring(4);
        } else if (method.getName().startsWith("is")) {
            if (method.getName().length() < 3) {
                return method.getName();
            }
            
            return
                    method.getName().substring(2, 3).toLowerCase()
                    + method.getName().substring(3);
        } else {
            return method.getName();
        }
        
    }
    
    private List<String> getVariablesToFetch(
            final String idPropertyName,
            final List<MethodParameter> parameters) {
        
        final var result = new LinkedList<String>();
        
        // the aggregate's id aka the business key
        result.add(idPropertyName);
        
        parameters
                .stream()
                .filter(parameter -> parameter instanceof ParameterVariables)
                .flatMap(parameter -> ((ParameterVariables) parameter).getVariables().stream())
                .forEach(result::add); 
        
        return result;
        
    }
    
    public void wireTask(
            final Camunda8ProcessService<?> processService,
            final Camunda8Connectable connectable) {
        
        super.wireTask(
                connectable,
                false,
                (method, annotation) -> methodMatchesTaskDefinition(connectable, method, annotation),
                (method, annotation) -> methodMatchesElementId(connectable, method, annotation),
                (method, annotation) -> validateParameters(processService, method),
                (bean, method, parameters) -> connectToBpms(processService, bean, connectable, method, parameters));
                
    }
    
}
