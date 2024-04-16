package io.vanillabp.camunda8.service;

import io.camunda.zeebe.client.ZeebeClient;
import io.vanillabp.camunda8.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.Camunda8VanillaBpProperties;
import io.vanillabp.springboot.adapter.AdapterAwareProcessService;
import io.vanillabp.springboot.adapter.ProcessServiceImplementation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.repository.CrudRepository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

@Transactional(propagation = Propagation.MANDATORY)
public class Camunda8ProcessService<DE>
        implements ProcessServiceImplementation<DE> {

    private static final Logger logger = LoggerFactory.getLogger(Camunda8ProcessService.class);
    
    private final CrudRepository<DE, Object> workflowAggregateRepository;

    private final Class<DE> workflowAggregateClass;

    private final Function<DE, Object> getWorkflowAggregateId;

    private final Camunda8VanillaBpProperties camunda8Properties;

    private final ApplicationEventPublisher publisher;

    private AdapterAwareProcessService<DE> parent;
    
    private ZeebeClient client;

    public Camunda8ProcessService(
            final Camunda8VanillaBpProperties camunda8Properties,
            final ApplicationEventPublisher publisher,
            final CrudRepository<DE, Object> workflowAggregateRepository,
            final Function<DE, Object> getWorkflowAggregateId,
            final Class<DE> workflowAggregateClass) {
        
        super();
        this.camunda8Properties = camunda8Properties;
        this.publisher = publisher;
        this.workflowAggregateRepository = workflowAggregateRepository;
        this.workflowAggregateClass = workflowAggregateClass;
        this.getWorkflowAggregateId = getWorkflowAggregateId;
                
    }
    
    @Override
    public void setParent(
            final AdapterAwareProcessService<DE> parent) {
        
        this.parent = parent;
        
    }
    
    public void wire(
            final ZeebeClient client,
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
    
    @Override
    public CrudRepository<DE, Object> getWorkflowAggregateRepository() {

        return workflowAggregateRepository;

    }

    @Override
    public DE startWorkflow(
            final DE workflowAggregate) throws Exception {

        return runInTransaction(
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
                                .get(10, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new RuntimeException(
                                "Starting workflow '"
                                + parent.getPrimaryBpmnProcessId()
                                + "‘ for aggregate '"
                                + attachedAggregate
                                + "' failed!",
                                e);
                    }
                });
        
    }

    @Override
    public DE correlateMessage(
            final DE workflowAggregate,
            final String messageName) {

        return runInTransaction(
                workflowAggregate,
                attachedAggregate -> {
                    final var correlationId = getWorkflowAggregateId
                            .apply(workflowAggregate);

                    doCorrelateMessage(
                            workflowAggregate,
                            messageName,
                            correlationId.toString());
                });

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

        return runInTransaction(
                workflowAggregate,
                attachedAggregate -> doCorrelateMessage(
                        attachedAggregate,
                        messageName,
                        correlationId));

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

    @Override
    public DE completeTask(
            final DE workflowAggregate,
            final String taskId) {

        return runInTransaction(
                workflowAggregate,
                taskId,
                attachedAggregate -> {
                    client
                            .newCompleteCommand(Long.parseLong(taskId, 16))
                            .variables(attachedAggregate)
                            .send()
                            .join();

                    logger.trace("Complete task '{}' of process '{}'",
                            taskId,
                            parent.getPrimaryBpmnProcessId());
                });

    }
    
    @Override
    public DE completeUserTask(
            final DE workflowAggregate,
            final String taskId) {

        return completeTask(workflowAggregate, taskId);
        
    }
    
    @Override
    public DE cancelTask(
            final DE workflowAggregate,
            final String taskId,
            final String errorCode) {

        return runInTransaction(
                workflowAggregate,
                taskId,
                attachedAggregate -> {
                    client
                            .newThrowErrorCommand(Long.parseLong(taskId))
                            .errorCode(errorCode)
                            .send()
                            .join();

                    logger.trace("Complete task '{}' of process '{}'",
                            taskId,
                            parent.getPrimaryBpmnProcessId());
                });

    }
    
    @Override
    public DE cancelUserTask(
            final DE workflowAggregate,
            final String taskId,
            final String errorCode) {

        return cancelTask(workflowAggregate, taskId, errorCode);

    }

    private DE runInTransaction(
            final DE workflowAggregate,
            final Consumer<DE> runnable) {

        return runInTransaction(
                workflowAggregate,
                null,
                runnable);

    }

    private DE runInTransaction(
            final DE workflowAggregate,
            final String taskIdToTestForAlreadyCompletedOrCancelled,
            final Consumer<DE> runnable) {

        // persist to get ID in case of @Id @GeneratedValue
        // or force optimistic locking exceptions before running
        // the workflow if aggregate was already persisted before
        final var attachedAggregate = workflowAggregateRepository
                .save(workflowAggregate);

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            if (taskIdToTestForAlreadyCompletedOrCancelled != null) {
                publisher.publishEvent(
                        new Camunda8TransactionProcessor.Camunda8TestForTaskAlreadyCompletedOrCancelled(
                                Camunda8TransactionInterceptor.class,
                                () -> client
                                        .newUpdateTimeoutCommand(Long.parseUnsignedLong(taskIdToTestForAlreadyCompletedOrCancelled, 16))
                                        .timeout(Duration.ofMinutes(10))
                                        .send()
                                        .join(5, TimeUnit.MINUTES), // needs to run synchronously
                                () -> "update timeout (BPMN: " + parent.getPrimaryBpmnProcessId() + ")"));            }
            publisher.publishEvent(
                    new Camunda8TransactionProcessor.Camunda8CommandAfterTx(
                            Camunda8TransactionInterceptor.class,
                            () -> runnable.accept(attachedAggregate),
                            () -> "complete command (BPMN: " + parent.getPrimaryBpmnProcessId() + ")"));
        } else {
            runnable.accept(attachedAggregate);
        }

        return attachedAggregate;

    }

}
