package io.vanillabp.camunda8.wiring;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.worker.JobWorkerBuilderStep1;
import io.camunda.zeebe.model.bpmn.impl.BpmnModelInstanceImpl;
import io.camunda.zeebe.model.bpmn.instance.BaseElement;
import io.camunda.zeebe.model.bpmn.instance.Process;
import io.camunda.zeebe.model.bpmn.instance.UserTask;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeFormDefinition;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeLoopCharacteristics;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskDefinition;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskListener;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskListenerEventType;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskListeners;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeUserTask;
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;

public class Camunda8TaskWiring extends TaskWiringBase<Camunda8Connectable, Camunda8ProcessService<?>, Camunda8MethodParameterFactory>
        implements Consumer<CamundaClient> {

    private static final Logger logger = LoggerFactory.getLogger(Camunda8TaskWiring.class);
    private static final String TASKDEFINITION_USERTASK_WORKER = "io.camunda.zeebe:userTask";
    private static final String TASKDEFINITION_USERTASK_ZEEBE = "io.vanillabp.userTask:";

    /*
     * timeout can be set to Long.MAX_VALUE but this will cause a subsequent error
     * (see https://github.com/camunda/camunda/issues/11903). Therefor the timeout
     * is set to 100 years.
     */
    private static final long TIMEOUT_100YEARS = 100L * 365 * 24 * 3600 * 1000;

    private final String workerId;
    
    private final SpringDataUtil springDataUtil;
    
    private final ObjectProvider<Camunda8TaskHandler> taskHandlers;

    private final Collection<Camunda8ProcessService<?>> connectableServices;
    
    private final Camunda8UserTaskHandler userTaskHandler;

    private CamundaClient client;
    
    private List<JobWorkerBuilderStep1.JobWorkerBuilderStep3> workers = new LinkedList<>();

    private List<Camunda8TaskHandler> handlers = new LinkedList<>();

    private Set<String> userTaskWorkflowModuleIds = new HashSet<>();
    
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
            final CamundaClient client) {
        
        this.client = client;
        handlers.forEach(handler -> handler.accept(client));

    }
    
    public void openWorkers() {

        // fetch all usertasks spawned
        userTaskWorkflowModuleIds
                .stream()
                .map(workflowModuleId -> {
                    final var tenantId = camunda8Properties.getTenantId(workflowModuleId);

                    // old user tasks
                    final var oldUserTaskWorker = client
                            .newWorker()
                            .jobType(TASKDEFINITION_USERTASK_WORKER)
                            .handler(userTaskHandler)
                            .timeout(TIMEOUT_100YEARS) // user-tasks should not be fetched more than once
                            .name(workerId);
                    if (tenantId != null) {
                        oldUserTaskWorker.tenantId(tenantId);
                    }

                    final var workerProperties = camunda8Properties.getUserTaskWorkerProperties(workflowModuleId);
                    workerProperties.applyToUserTaskWorker(oldUserTaskWorker);

                    return oldUserTaskWorker;
                })
                .forEach(workers::add);

        workers
                .forEach(JobWorkerBuilderStep1.JobWorkerBuilderStep3::open);
        
    }

    public Stream<Camunda8Connectable> connectablesForType(
            final Process process,
            final BpmnModelInstanceImpl model,
            final Class<? extends BaseElement> type,
            final boolean allowConnectors,
            final boolean isNewProcess) {

        if (!process.isExecutable()) {
            return Stream.empty();
        }

        return model
                .getModelElementsByType(type)
                .stream()
                .filter(element -> !allowConnectors || hasModelerTemplateAttributeSetForConnector(element))
                .filter(element -> Objects.equals(getOwningProcess(element), process))
                .flatMap(element -> {
                    final List<Camunda8Connectable> result = new LinkedList<>();

                    final var isZeebeUserTask = element.getSingleExtensionElement(ZeebeUserTask.class) != null;
                    if (isZeebeUserTask) { // Camunda user task
                        getExistingTaskListenersJobTypes(element)
                                .stream()
                                .map(jobType ->new Camunda8Connectable(
                                        process,
                                        element.getId(),
                                        jobType.startsWith(TASKDEFINITION_USERTASK_ZEEBE) ? Type.USERTASK_ZEEBE : Type.TASK,
                                        jobType,
                                        element.getSingleExtensionElement(ZeebeLoopCharacteristics.class)))
                                .forEach(result::add);
                        if (isNewProcess) {
                            Optional
                                    .ofNullable(element.getSingleExtensionElement(ZeebeFormDefinition.class))
                                    .map(ZeebeFormDefinition::getExternalReference)
                                    .ifPresentOrElse(externalFormReference -> {
                                            addTaskListenersToBpmnModel(externalFormReference, element);
                                            result
                                                        .add(new Camunda8Connectable(
                                                                process,
                                                                element.getId(),
                                                                Type.USERTASK_ZEEBE,
                                                                externalFormReference,
                                                                element.getSingleExtensionElement(ZeebeLoopCharacteristics.class)));
                                        },
                                        () -> {
                                            throw new RuntimeException(
                                                        "Found user task '"
                                                        + element.getId()
                                                        + "' of process '"
                                                        + process.getId()
                                                        + "' having no external reference set!");
                                        });
                        }
                    } else if (UserTask.class.isAssignableFrom(type)) { // worker-based user task
                        Optional
                                .ofNullable(element.getSingleExtensionElement(ZeebeFormDefinition.class))
                                .map(ZeebeFormDefinition::getFormKey)
                                .ifPresentOrElse(formKey -> result
                                        .add(new Camunda8Connectable(
                                                process,
                                                element.getId(),
                                                Type.USERTASK,
                                                formKey,
                                                element.getSingleExtensionElement(ZeebeLoopCharacteristics.class))),
                                        () -> {
                                            throw new RuntimeException(
                                                    "Found user task '"
                                                    + element.getId()
                                                    + "' of process '"
                                                    + process.getId()
                                                    + "' having no form key set!");
                                        });
                    } else {
                        Optional
                                .ofNullable(element.getSingleExtensionElement(ZeebeTaskDefinition.class))
                                .map(ZeebeTaskDefinition::getType)
                                .ifPresent(taskDefinition -> result
                                        .add(new Camunda8Connectable(
                                                process,
                                                element.getId(),
                                                Type.TASK,
                                                taskDefinition,
                                                element.getSingleExtensionElement(ZeebeLoopCharacteristics.class))));
                    }

                    return result.stream();
                })
                .filter(Objects::nonNull);

    }

    private List<String> getExistingTaskListenersJobTypes(
            final BaseElement element) {

        return Optional
                .ofNullable(element.getSingleExtensionElement(ZeebeTaskListeners.class))
                .stream()
                .flatMap(zeebeTaskListeners -> zeebeTaskListeners.getTaskListeners().stream())
                .map(ZeebeTaskListener::getType)
                .toList();

    }

    private void addTaskListenersToBpmnModel(
            final String externalFormReference,
            final BaseElement element) {

        final ZeebeTaskListeners taskListeners;
        if (element.getSingleExtensionElement(ZeebeTaskListeners.class) != null) {
            taskListeners = element.getSingleExtensionElement(ZeebeTaskListeners.class);
        } else {
            taskListeners = element.getExtensionElements().addExtensionElement(ZeebeTaskListeners.class);
        }

        final var createListener = element.getModelInstance().newInstance(ZeebeTaskListener.class);
        createListener.setEventType(ZeebeTaskListenerEventType.creating);
        createListener.setType(TASKDEFINITION_USERTASK_ZEEBE + externalFormReference);
        createListener.setRetries("0");
        taskListeners.insertElementAfter(createListener, null); // insert as first listener

        final var cancelListener = element.getModelInstance().newInstance(ZeebeTaskListener.class);
        cancelListener.setEventType(ZeebeTaskListenerEventType.canceling);
        cancelListener.setType(TASKDEFINITION_USERTASK_ZEEBE + externalFormReference);
        cancelListener.setRetries("0");

        if (taskListeners.getTaskListeners().isEmpty()) {
            taskListeners.insertElementAfter(createListener, createListener);
        } else {
            final var lastListener = new LinkedList<>(taskListeners.getTaskListeners())
                    .getLast();
            taskListeners.insertElementAfter(cancelListener, lastListener);
        }

    }

    private boolean hasModelerTemplateAttributeSetForConnector(BaseElement element) {
        return element.getAttributeValueNs("http://camunda.org/schema/zeebe/1.0", "modelerTemplate") == null;
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
                idPropertyName,
                tenantId,
                workflowModuleId,
                processService.getPrimaryBpmnProcessId());
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
            userTaskWorkflowModuleIds.add(workflowModuleId);
            return;
            
        }

        final var variablesToFetch = getVariablesToFetch(idPropertyName, parameters);
        final var worker = client
                .newWorker()
                .jobType(connectable.getType() == Type.USERTASK_ZEEBE
                        ? TASKDEFINITION_USERTASK_ZEEBE + connectable.getTaskDefinition()
                        : connectable.getTaskDefinition())
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
                .filter(field -> (field.getAnnotation(Id.class) != null)
                        || (field.getAnnotation(org.springframework.data.annotation.Id.class) != null))
                .findFirst()
                .map(Field::getName)
                .orElse(Arrays
                        .stream(workflowAggregateClass.getDeclaredMethods())
                        .filter(method -> (method.getAnnotation(Id.class) != null)
                                || (method.getAnnotation(org.springframework.data.annotation.Id.class) != null))
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
