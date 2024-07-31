package io.vanillabp.camunda8.wiring;

import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.worker.JobWorkerBuilderStep1.JobWorkerBuilderStep3;
import io.camunda.zeebe.model.bpmn.impl.BpmnModelInstanceImpl;
import io.camunda.zeebe.model.bpmn.instance.BaseElement;
import io.camunda.zeebe.model.bpmn.instance.Process;
import io.camunda.zeebe.model.bpmn.instance.UserTask;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeFormDefinition;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeLoopCharacteristics;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskDefinition;
import io.vanillabp.camunda8.Camunda8VanillaBpProperties;
import io.vanillabp.camunda8.deployment.Camunda8DeploymentAdapter;
import io.vanillabp.camunda8.deployment.DeployedBpmn;
import io.vanillabp.camunda8.service.Camunda8ProcessService;
import io.vanillabp.camunda8.wiring.Camunda8Connectable.Type;
import io.vanillabp.camunda8.wiring.parameters.Camunda8MethodParameterFactory;
import io.vanillabp.camunda8.wiring.parameters.ParameterVariables;
import io.vanillabp.spi.service.WorkflowTask;
import io.vanillabp.springboot.adapter.SpringBeanUtil;
import io.vanillabp.springboot.adapter.SpringDataUtil;
import io.vanillabp.springboot.adapter.TaskWiringBase;
import io.vanillabp.springboot.parameters.MethodParameter;
import jakarta.persistence.Id;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class Camunda8TaskWiring extends TaskWiringBase<Camunda8Connectable, Camunda8ProcessService<?>, Camunda8MethodParameterFactory>
        implements Consumer<ZeebeClient> {

    private final String workerId;
    
    private final SpringDataUtil springDataUtil;
    
    private final ObjectProvider<Camunda8TaskHandler> taskHandlers;

    private final Collection<Camunda8ProcessService<?>> connectableServices;
    
    private final Camunda8UserTaskHandler userTaskHandler;

    private ZeebeClient client;
    
    private List<JobWorkerBuilderStep3> workers = new LinkedList<>();

    private List<Camunda8TaskHandler> handlers = new LinkedList<>();

    private Set<String> userTaskTenantIds = new HashSet<>();
    
    private final Camunda8VanillaBpProperties camunda8Properties;
    
    public Camunda8TaskWiring(
            final SpringDataUtil springDataUtil,
            final ApplicationContext applicationContext,
            final SpringBeanUtil springBeanUtil,
            final String workerId,
            final Camunda8VanillaBpProperties camunda8Properties,
            final Camunda8UserTaskHandler userTaskHandler,
            final ObjectProvider<Camunda8TaskHandler> taskHandlers,
            final Collection<Camunda8ProcessService<?>> connectableServices) {
        
        super(applicationContext, springBeanUtil, new Camunda8MethodParameterFactory());
        this.workerId = workerId;
        this.springDataUtil = springDataUtil;
        this.taskHandlers = taskHandlers;
        this.userTaskHandler = userTaskHandler;
        this.connectableServices = connectableServices;
        this.camunda8Properties = camunda8Properties;
        
    }
    
    @Override
    protected Class<WorkflowTask> getAnnotationType() {
        
        return WorkflowTask.class;
        
    }
    
    /**
     * Called by
     * {@link Camunda8DeploymentAdapter#processBpmnModel(String, Map, DeployedBpmn, BpmnModelInstanceImpl, boolean)} to
     * ensure client is available before using wire-methods.
     */
    @Override
    public void accept(
            final ZeebeClient client) {
        
        this.client = client;
        handlers.forEach(handler -> handler.accept(client));

    }
    
    public void openWorkers() {

        // fetch all usertasks spawned
        userTaskTenantIds
                .stream()
                .map(workflowModuleId -> {
                    final var tenantId = camunda8Properties.getTenantId(workflowModuleId);
                    final var userTaskWorker = client
                            .newWorker()
                            .jobType("io.camunda.zeebe:userTask")
                            .handler(userTaskHandler)
                            .timeout(Integer.MAX_VALUE) // user-tasks are not fetched more than once
                            .name(workerId)
                            .tenantId(tenantId);
                    final var workerProperties = camunda8Properties.getUserTaskWorkerProperties(workflowModuleId);
                    workerProperties.applyToUserTaskWorker(userTaskWorker);
                    return userTaskWorker;
                })
                .forEach(workers::add);

        workers
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
            final String workflowModuleId,
            final Camunda8ProcessService<?> processService,
            final Object bean,
            final Camunda8Connectable connectable,
            final Method method,
            final List<MethodParameter> parameters) {
        
        final var repository = processService.getWorkflowAggregateRepository();
        final var idPropertyName = getWorkflowAggregateIdPropertyName(
                processService.getWorkflowAggregateClass());
        final var tenantId = camunda8Properties.getTenantId(workflowModuleId);

        final var taskHandler = taskHandlers.getObject(
                springDataUtil,
                repository,
                connectable.getType(),
                connectable.getTaskDefinition(),
                bean,
                method,
                parameters,
                idPropertyName);
        if (this.client != null) {
            taskHandler.accept(this.client);
        } else {
            handlers.add(taskHandler);
        }

        if (connectable.getType() == Type.USERTASK) {

            userTaskHandler.addTaskHandler(
                    tenantId,
                    connectable.getBpmnProcessId(),
                    connectable.getElementId(),
                    taskHandler);
            userTaskTenantIds.add(workflowModuleId);
            return;
            
        }

        final var variablesToFetch = getVariablesToFetch(idPropertyName, parameters);
        final var worker = client
                .newWorker()
                .jobType(connectable.getTaskDefinition())
                .handler(taskHandler)
                .name(workerId)
                .fetchVariables(variablesToFetch);

        final var workerProperties = camunda8Properties.getWorkerProperties(
                workflowModuleId,
                connectable.getBpmnProcessId(),
                connectable.getTaskDefinition());
        workerProperties.applyToWorker(worker);

        workers.add(
                tenantId != null
                        ? worker.tenantId(tenantId)
                        : worker);
        
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
                .map(Field::getName)
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
            final String workflowModuleId,
            final Camunda8ProcessService<?> processService,
            final Camunda8Connectable connectable) {
        
        super.wireTask(
                connectable,
                false,
                (method, annotation) -> methodMatchesTaskDefinition(connectable, method, annotation),
                (method, annotation) -> methodMatchesElementId(connectable, method, annotation),
                (method, annotation) -> validateParameters(processService, method),
                (bean, method, parameters) -> connectToBpms(workflowModuleId, processService, bean, connectable, method, parameters));
                
    }
    
}
