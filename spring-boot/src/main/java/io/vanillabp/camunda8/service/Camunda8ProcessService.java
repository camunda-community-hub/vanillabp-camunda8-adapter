package io.vanillabp.camunda8.service;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.JsonMapper;
import io.camunda.client.api.search.response.ProcessInstance;
import io.vanillabp.camunda8.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.Camunda8VanillaBpProperties;
import io.vanillabp.camunda8.LoggingContext;
import io.vanillabp.camunda8.service.bpmn.ProcessDefinitionCollector;
import io.vanillabp.camunda8.service.bpmn.ProcessExecutionHistoryCollector;
import io.vanillabp.camunda8.wiring.Camunda8TaskHandler;
import io.vanillabp.spi.process.ProcessDefinition;
import io.vanillabp.spi.process.ProcessDefinitionNotFoundException;
import io.vanillabp.spi.process.WorkflowHistory;
import io.vanillabp.spi.process.WorkflowNotFoundException;
import io.vanillabp.springboot.adapter.AdapterAwareProcessService;
import io.vanillabp.springboot.adapter.ProcessServiceImplementation;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.repository.CrudRepository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Transactional(propagation = Propagation.MANDATORY)
public class Camunda8ProcessService<DE>
        implements ProcessServiceImplementation<DE> {

    private static final Logger logger = LoggerFactory.getLogger(Camunda8ProcessService.class);

    private final CrudRepository<DE, Object> workflowAggregateRepository;

    private final Class<DE> workflowAggregateClass;

    private final Function<DE, Object> getWorkflowAggregateId;

    private final String workflowAggregateIdAttributeName;

    private final Camunda8VanillaBpProperties camunda8Properties;

    private final ApplicationEventPublisher publisher;

    private final JsonMapper camundaJsonMapper;

    private ProcessDefinitionCollector processDefinitionCollector;

    private ProcessExecutionHistoryCollector processExecutionHistoryCollector;

    private AdapterAwareProcessService<DE> parent;

    private CamundaClient client;

    public Camunda8ProcessService(
            final Camunda8VanillaBpProperties camunda8Properties,
            final ApplicationEventPublisher publisher,
            final JsonMapper camundaJsonMapper,
            final CrudRepository<DE, Object> workflowAggregateRepository,
            final Function<DE, Object> getWorkflowAggregateId,
            final Class<DE> workflowAggregateClass,
            final String workflowAggregateIdAttributeName) {

        super();
        this.camunda8Properties = camunda8Properties;
        this.publisher = publisher;
        this.camundaJsonMapper = camundaJsonMapper;
        this.workflowAggregateRepository = workflowAggregateRepository;
        this.workflowAggregateClass = workflowAggregateClass;
        this.getWorkflowAggregateId = getWorkflowAggregateId;
        this.workflowAggregateIdAttributeName = workflowAggregateIdAttributeName;

    }

    @Override
    public void setParent(
            final AdapterAwareProcessService<DE> parent) {

        this.parent = parent;

    }

    @Override
    public String getWorkflowModuleId() {

        return parent.getWorkflowModuleId();

    }

    public void wire(
            final CamundaClient client,
            final String workflowModuleId,
            final String bpmnProcessId,
            final boolean isPrimary,
            final Collection<String> messageBasedStartEventsMessageNames,
            final Collection<String> signalBasedStartEventsSignalNames) {

        if (parent == null) {
            throw new RuntimeException("Not yet wired! If this occurs dependency of either "
                    + "VanillaBP Spring Boot support or Camunda8 adapter was changed introducing this "
                    + "lack of wiring. Please report a Github issue!");

        }

        this.client = client;
        this.processDefinitionCollector = new ProcessDefinitionCollector(client);
        this.processExecutionHistoryCollector = new ProcessExecutionHistoryCollector(client);

        parent.wire(
                Camunda8AdapterConfiguration.ADAPTER_ID,
                workflowModuleId,
                bpmnProcessId,
                isPrimary,
                messageBasedStartEventsMessageNames,
                signalBasedStartEventsSignalNames);

    }

    @Override
    public Class<DE> getWorkflowAggregateClass() {

        return workflowAggregateClass;

    }

    public String getWorkflowAggregateIdAttributeName() {

        return workflowAggregateIdAttributeName;

    }

    @Override
    public CrudRepository<DE, Object> getWorkflowAggregateRepository() {

        return workflowAggregateRepository;

    }

    @Override
    public String getPrimaryBpmnProcessId() {
        return parent.getPrimaryBpmnProcessId();
    }

    @Override
    public DE startWorkflow(
            final DE workflowAggregate) {

        return completeAfterTransaction(
                workflowAggregate,
                attachedAggregate -> {
                    final var tenantId = camunda8Properties.getTenantId(parent.getWorkflowModuleId());
                    final var command = client
                            .newCreateInstanceCommand()
                            .bpmnProcessId(parent.getPrimaryBpmnProcessId())
                            .latestVersion()
                            .variables(attachedAggregate);

                    try {
                        (tenantId == null
                                ? command
                                : command.tenantId(tenantId))
                                .send()
                                .get();
                    } catch (Exception e) {
                        throw new RuntimeException(
                                "Starting workflow '"
                                + parent.getPrimaryBpmnProcessId()
                                + "‘ for aggregate '"
                                + attachedAggregate
                                + "' failed!",
                                e);
                    }
                },
                "startWorkflow");

    }

    @Override
    public DE startWorkflowByMessage(
            final DE workflowAggregate,
            final String messageName) {

        return correlateMessage(workflowAggregate, messageName);

    }

    @Override
    public DE startWorkflowByMessage(
            final DE workflowAggregate,
            final Object message) {

        return correlateMessage(workflowAggregate, message);

    }

    @Override
    public DE correlateMessage(
            final DE workflowAggregate,
            final String messageName) {

        return completeAfterTransaction(
                workflowAggregate,
                attachedAggregate -> {
                    final var correlationId = getWorkflowAggregateId
                            .apply(workflowAggregate);

                    doCorrelateMessage(
                            workflowAggregate,
                            messageName,
                            correlationId.toString());
                },
                "correlateMessage");

    }

    @Override
    public DE correlateMessage(
            final DE workflowAggregate,
            final Object message) {

        return correlateMessage(
                workflowAggregate,
                message.getClass().getSimpleName());

    }

    @Override
    public DE correlateMessage(
            final DE workflowAggregate,
            final String messageName,
            final String correlationId) {

        return completeAfterTransaction(
                workflowAggregate,
                attachedAggregate -> doCorrelateMessage(
                        attachedAggregate,
                        messageName,
                        correlationId),
                "correlateMessage-by-correlationId");

    }

    private void doCorrelateMessage(
            final DE attachedAggregate,
            final String messageName,
            final String correlationId) {

        final var tenantId = camunda8Properties.getTenantId(parent.getWorkflowModuleId());
        final var command = client
                .newPublishMessageCommand()
                .messageName(messageName)
                .correlationKey(correlationId)
                .variables(attachedAggregate);

        final var messageKey = (tenantId == null
                ? command
                : command.tenantId(tenantId))
                .send()
                .join()
                .getMessageKey();

        logger.trace("Correlated message '{}' using correlation-id '{}' for process '{}' as '{}'",
                messageName,
                correlationId,
                parent.getPrimaryBpmnProcessId(),
                messageKey);

    }

    @Override
    public DE correlateMessage(
            final DE workflowAggregate,
            final Object message,
            final String correlationId) {

        return correlateMessage(
                workflowAggregate,
                message.getClass().getSimpleName(),
                correlationId);

    }

    private long getTaskIdAsLong(
            final String taskId) {

        return camunda8Properties.isTaskIdAsHexString(parent.getWorkflowModuleId())
                ? Long.parseLong(taskId, 16)
                : Long.parseLong(taskId);

    }

    @Override
    public DE completeTask(
            final DE workflowAggregate,
            final String taskId) {

        return processTaskAfterTransaction(
                workflowAggregate,
                taskId,
                attachedAggregate -> {
                    client
                            .newCompleteCommand(getTaskIdAsLong(taskId))
                            .variables(attachedAggregate)
                            .send()
                            .join();
                    logger.trace("Complete task '{}' of process '{}'",
                            taskId,
                            parent.getPrimaryBpmnProcessId());
                },
                "completeTask");

    }

    @Override
    public DE completeUserTask(
            final DE workflowAggregate,
            final String taskId) {

        final var result = completeUserTaskAfterTransaction(
                workflowAggregate,
                taskId,
                "completeUserTask",
                null);

        logger.trace("Completed user task '{}' of process '{}'",
                taskId,
                parent.getPrimaryBpmnProcessId());
        return result;

    }

    @Override
    public DE cancelTask(
            final DE workflowAggregate,
            final String taskId,
            final String errorCode) {

        return processTaskAfterTransaction(
                workflowAggregate,
                taskId,
                attachedAggregate -> {
                    client
                            .newThrowErrorCommand(getTaskIdAsLong(taskId))
                            .errorCode(errorCode)
                            .send()
                            .join();
                    logger.trace("Canceled task '{}' of process '{}'",
                            taskId,
                            parent.getPrimaryBpmnProcessId());
                },
                "cancelTask");

    }

    @Override
    public DE cancelUserTask(
            final DE workflowAggregate,
            final String taskId,
            final String errorCode) {

        final var zeebeUserTask = client
                .newUserTaskGetRequest(getTaskIdAsLong(taskId))
                .send()
                .join();

        if (zeebeUserTask != null) {

            final var result = completeUserTaskAfterTransaction(
                    workflowAggregate,
                    taskId,
                    "cancelZeebeUserTask",
                    errorCode);

            logger.trace("Canceled user task '{}' of process '{}'",
                    taskId,
                    parent.getPrimaryBpmnProcessId());

            return result;

        }

        return processTaskAfterTransaction(
                workflowAggregate,
                taskId,
                attachedAggregate -> {
                    client
                            .newThrowErrorCommand(getTaskIdAsLong(taskId))
                            .errorCode(errorCode)
                            .send()
                            .join();
                    logger.trace("Canceled user task '{}' of process '{}'",
                            taskId,
                            parent.getPrimaryBpmnProcessId());
                },
                "cancelUserTask");

    }

    @FunctionalInterface
    private interface RunConsumer<DE> {
        void accept(String bpmnProcessId, DE attachedAggregate, Object aggregateId);
    }

    private DE run(
            final DE workflowAggregate,
            final String taskIdToTestForAlreadyCompletedOrCancelled,
            final RunConsumer<DE> runnable,
            final String methodSignature) {

        try {

            // persist to get ID in case of @Id @GeneratedValue
            // or force optimistic locking exceptions before running
            // the workflow if aggregate was already persisted before
            final var attachedAggregate = workflowAggregateRepository
                    .save(workflowAggregate);

            final var aggregateId = getWorkflowAggregateId.apply(attachedAggregate);
            final var bpmnProcessId = parent.getPrimaryBpmnProcessId();
            LoggingContext.setLoggingContext(
                    Camunda8AdapterConfiguration.ADAPTER_ID,
                    camunda8Properties.getTenantId(parent.getWorkflowModuleId()),
                    parent.getWorkflowModuleId(),
                    aggregateId.toString(),
                    bpmnProcessId,
                    taskIdToTestForAlreadyCompletedOrCancelled,
                    null,
                    null,
                    null);

            runnable.accept(bpmnProcessId, attachedAggregate, aggregateId);

            return attachedAggregate;

        } finally {
                LoggingContext.clearContext();
        }

    }

    private DE completeUserTaskAfterTransaction(
            final DE workflowAggregate,
            final String taskId,
            final String methodSignature,
            final String errorCode) {

        final var taskIdAsLong = getTaskIdAsLong(taskId);
        return run(
                workflowAggregate,
                taskId,
                (bpmnProcessId, attachedAggregate, aggregateId) -> {
                    final var postCommitEvent = new Camunda8TransactionProcessor.Camunda8CommandAfterTx(
                            methodSignature,
                            () -> {
                                var command = client
                                        .newCompleteUserTaskCommand(taskIdAsLong)
                                        .variables(attachedAggregate);
                                if (errorCode != null) {
                                    command = command.variable(Camunda8TaskHandler.BPMN_ERROR_VARIABLE, errorCode);
                                } else {
                                    // TODO: cannot set both, aggregate and error variable since client does not accept this
                                    // command = command.variable(Camunda8TaskHandler.BPMN_ERROR_VARIABLE, "");
                                }
                                command.send().join();
                            },
                            () -> client
                                    .newCompleteCommand(taskIdAsLong)
                                    .variables(attachedAggregate)
                                    .send()
                                    .join(),
                            () -> "aggregate: "
                                    + aggregateId
                                    + "; bpmn-process-id: "
                                    + bpmnProcessId);
                    if (TransactionSynchronizationManager.isActualTransactionActive()) {
                        publisher.publishEvent(
                                new Camunda8TransactionProcessor.Camunda8TestForTaskAlreadyCompletedOrCancelled(
                                        methodSignature,
                                        () -> client
                                                .newUserTaskGetRequest(taskIdAsLong)
                                                .send()
                                                .join(5, TimeUnit.MINUTES), // needs to run synchronously
                                        () -> client
                                                .newUpdateTimeoutCommand(taskIdAsLong)
                                                .timeout(Duration.ofMinutes(10))
                                                .send()
                                                .join(5, TimeUnit.MINUTES), // needs to run synchronously
                                        () -> "UserTaskGet on '"
                                                + taskId
                                                + "' for aggregate: "
                                                + aggregateId
                                                + "; bpmn-process-id: "
                                                + bpmnProcessId));
                        publisher.publishEvent(postCommitEvent);
                    } else {
                        new Camunda8TransactionProcessor().processPostCommit(postCommitEvent);
                    }
                },
                methodSignature);

    }

    private DE processTaskAfterTransaction(
            final DE workflowAggregate,
            final String taskId,
            final Consumer<DE> runnable,
            final String methodSignature) {

        final var taskIdAsLong = getTaskIdAsLong(taskId);
        return run(
                workflowAggregate,
                taskId,
                (bpmnProcessId, attachedAggregate, aggregateId) -> {
                    if (TransactionSynchronizationManager.isActualTransactionActive()) {
                        publisher.publishEvent(
                                new Camunda8TransactionProcessor.Camunda8TestForTaskAlreadyCompletedOrCancelled(
                                        methodSignature,
                                        () -> client
                                                .newUpdateTimeoutCommand(taskIdAsLong)
                                                .timeout(Duration.ofMinutes(10))
                                                .send()
                                                .join(5, TimeUnit.MINUTES), // needs to run synchronously
                                        null,
                                        () -> "UpdateTimeout on '"
                                                + taskId
                                                + "' for aggregate: "
                                                + aggregateId
                                                + "; bpmn-process-id: "
                                                + bpmnProcessId));
                        publisher.publishEvent(
                                new Camunda8TransactionProcessor.Camunda8CommandAfterTx(
                                        methodSignature,
                                        () -> runnable.accept(attachedAggregate),
                                        null,
                                        () -> "aggregate: "
                                                + aggregateId
                                                + "; bpmn-process-id: "
                                                + bpmnProcessId));
                    } else {
                        client
                                .newCompleteCommand(taskIdAsLong)
                                .variables(attachedAggregate)
                                .send()
                                .join();
                    }
                },
                methodSignature);

    }

    private DE completeAfterTransaction(
            final DE workflowAggregate,
            final Consumer<DE> runnable,
            final String methodSignature) {

        return run(
                workflowAggregate,
                null,
                (bpmnProcessId, attachedAggregate, aggregateId) -> {
                    if (TransactionSynchronizationManager.isActualTransactionActive()) {
                        publisher.publishEvent(
                                new Camunda8TransactionProcessor.Camunda8CommandAfterTx(
                                        methodSignature,
                                        () -> runnable.accept(attachedAggregate),
                                        null,
                                        () -> "aggregate: "
                                                + aggregateId
                                                + "; bpmn-process-id: "
                                                + bpmnProcessId));
                    } else {
                        runnable.accept(attachedAggregate);
                    }
                },
                methodSignature);

    }

    @Override
    public List<ProcessDefinition> getProcessDefinitions(
            final DE workflowAggregate,
            final String historyContext) throws WorkflowNotFoundException {

        final var aggregateId = getWorkflowAggregateId.apply(workflowAggregate).toString();
        final var tenantId = camunda8Properties.getTenantId(parent.getWorkflowModuleId());

        try {
            ProcessInstance processInstance;

            if (historyContext == null) {
                processInstance = findRootProcessInstance(aggregateId, tenantId);
            } else {
                processInstance = client
                        .newProcessInstanceGetRequest(Long.parseLong(historyContext))
                        .send()
                        .join();
            }

            if (processInstance == null) {
                throw new WorkflowNotFoundException(aggregateId);
            }

            return processDefinitionCollector.collectAllDefinitions(
                    processInstance, tenantId);

        } catch (WorkflowNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Error collecting process definitions", e);
        }
    }

    public InputStream getBpmnXml(
            final String processDefinitionId) throws ProcessDefinitionNotFoundException {

        try {
            final var xml = processDefinitionCollector.loadBpmnXml(Long.parseLong(processDefinitionId));
            return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new ProcessDefinitionNotFoundException(processDefinitionId, e);
        }

    }

    public WorkflowHistory getWorkflowHistory(
            final DE workflowAggregate,
            final String historyContext) throws WorkflowNotFoundException {

        final var aggregateId = getWorkflowAggregateId.apply(workflowAggregate);
        final var tenantId = camunda8Properties.getTenantId(parent.getWorkflowModuleId());

        try {
            ProcessInstance processInstance = findRootProcessInstance(aggregateId, tenantId);

            if (processInstance == null) {
                throw new WorkflowNotFoundException(aggregateId.toString());
            }

            String processInstanceId = historyContext == null
                    ? String.valueOf(processInstance.getProcessInstanceKey())
                    : historyContext;

            return processExecutionHistoryCollector.collectHistory(
                    processInstanceId, tenantId);

        } catch (WorkflowNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Error collecting workflow history", e);
        }
    }

    private ProcessInstance findRootProcessInstance(
            final Object aggregateId,
            final String tenantId) {

        final var result = client
                .newProcessInstanceSearchRequest()
                .filter(filter -> filter
                        //.tenantId(tenantId)
                        .processDefinitionId(parent.getPrimaryBpmnProcessId())
                        .variables(Map.of(workflowAggregateIdAttributeName,
                                camundaJsonMapper.toJson(aggregateId))))
                .send()
                .join();

        return result
                .items()
                .stream()
                .findFirst()
                .orElse(null);

    }
    
}
