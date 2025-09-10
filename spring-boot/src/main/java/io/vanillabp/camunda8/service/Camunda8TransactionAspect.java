package io.vanillabp.camunda8.service;

import io.vanillabp.spi.service.TaskException;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Aspect
public class Camunda8TransactionAspect {

    private static final Logger logger = LoggerFactory.getLogger(Camunda8TransactionAspect.class);

    public static class CommandWithFallback {
        public Runnable command;
        public Runnable fallback;
        public Supplier<String> descriptor;
    }
    public static class TaskHandlerActions {
        public Supplier<CommandWithFallback> testForTaskAlreadyCompletedOrCancelledCommand;
        public Map.Entry<Consumer<TaskException>, Function<TaskException, String>> bpmnErrorCommand;
        public Map.Entry<Consumer<Exception>, Function<Exception, String>> handlerFailedCommand;
        public Supplier<CommandWithFallback> handlerCompletedCommand;
    }

    public static class RunDeferredInTransaction {
        public RunDeferredInTransactionSupplier[] argsSupplier;
        public Runnable saveAggregateAfterWorkflowTask;
    }

    public interface RunDeferredInTransactionSupplier extends Supplier<Object> { }

    public static final ThreadLocal<TaskHandlerActions> actions = ThreadLocal.withInitial(TaskHandlerActions::new);

    public static final ThreadLocal<RunDeferredInTransaction> runDeferredInTransaction = ThreadLocal.withInitial(RunDeferredInTransaction::new);

    private final ApplicationEventPublisher publisher;

    public Camunda8TransactionAspect(
            final ApplicationEventPublisher publisher) {

        this.publisher = publisher;

    }

    public static void registerDeferredInTransaction(
            final RunDeferredInTransactionSupplier[] argsSupplier,
            final Runnable saveAggregateAfterWorkflowTask) {

        final var deferred = runDeferredInTransaction.get();
        deferred.argsSupplier = argsSupplier;
        deferred.saveAggregateAfterWorkflowTask = saveAggregateAfterWorkflowTask;

    }

    public static void unregisterDeferredInTransaction() {

        final var deferred = runDeferredInTransaction.get();
        deferred.argsSupplier = null;
        deferred.saveAggregateAfterWorkflowTask = null;

    }

    private void saveWorkflowAggregate() {

        final var deferred = runDeferredInTransaction.get();
        // sve only if activity currently executed is part of C8 and
        // not part of other adapters like (C7)
        if (deferred.saveAggregateAfterWorkflowTask != null) {
            deferred.saveAggregateAfterWorkflowTask.run();
        }

    }

    @Around("@annotation(io.vanillabp.spi.service.WorkflowTask)")
    private Object checkForTransaction(
            final ProceedingJoinPoint pjp) throws Throwable {

        final var methodSignature = pjp.getSignature().toLongString();

        final var isTxActive = TransactionSynchronizationManager.isActualTransactionActive();

        try {

            final var newArgs = runDeferredInTransactionArgsSupplier(pjp.getArgs());
            final var value = pjp.proceed(newArgs); // run @WorkflowTask annotated method
            saveWorkflowAggregate();

            if (isTxActive
                    && (actions.get().testForTaskAlreadyCompletedOrCancelledCommand != null)) {
                final var handlerTestCommand = actions.get().testForTaskAlreadyCompletedOrCancelledCommand.get();
                if (handlerTestCommand != null) {
                    publisher.publishEvent(
                            new Camunda8TransactionProcessor.Camunda8TestForTaskAlreadyCompletedOrCancelled(
                                    methodSignature,
                                    handlerTestCommand.command,
                                    handlerTestCommand.fallback,
                                    handlerTestCommand.descriptor));
                }
            }
            if (actions.get().handlerCompletedCommand != null) {
                final var handlerCompletedCommand = actions.get().handlerCompletedCommand.get();
                if (handlerCompletedCommand != null) {
                    final var postCommitEvent = new Camunda8TransactionProcessor.Camunda8CommandAfterTx(
                            methodSignature,
                            handlerCompletedCommand.command,
                            handlerCompletedCommand.fallback,
                            handlerCompletedCommand.descriptor);
                    if (isTxActive) {
                        publisher.publishEvent(postCommitEvent);
                    } else {
                        new Camunda8TransactionProcessor().processPostCommit(postCommitEvent);
                    }
                }
            }
            return value;

        } catch (TaskException taskError) {

            if (isTxActive
                    && (actions.get().testForTaskAlreadyCompletedOrCancelledCommand != null)) {
                final var handlerTestCommand = actions.get().testForTaskAlreadyCompletedOrCancelledCommand.get();
                if (handlerTestCommand != null) {
                    publisher.publishEvent(
                            new Camunda8TransactionProcessor.Camunda8TestForTaskAlreadyCompletedOrCancelled(
                                    methodSignature,
                                    handlerTestCommand.command,
                                    handlerTestCommand.fallback,
                                    handlerTestCommand.descriptor));
                }
            }
            if (actions.get().bpmnErrorCommand != null) {
                final var runnable = actions.get().bpmnErrorCommand.getKey();
                final var description = actions.get().bpmnErrorCommand.getValue();
                final var postCommitEvent = new Camunda8TransactionProcessor.Camunda8CommandAfterTx(
                        methodSignature,
                        () -> runnable.accept(taskError),
                        null,
                        () -> description.apply(taskError));
                if (isTxActive) {
                    publisher.publishEvent(postCommitEvent);
                } else {
                    new Camunda8TransactionProcessor().processPostCommit(postCommitEvent);
                }
            }
            return null;

        } catch (Exception e) {

            if (isTxActive
                    && (actions.get().testForTaskAlreadyCompletedOrCancelledCommand != null)) {
                final var handlerTestCommand = actions.get().testForTaskAlreadyCompletedOrCancelledCommand.get();
                if (handlerTestCommand != null) {
                    publisher.publishEvent(
                            new Camunda8TransactionProcessor.Camunda8TestForTaskAlreadyCompletedOrCancelled(
                                    methodSignature,
                                    handlerTestCommand.command,
                                    handlerTestCommand.fallback,
                                    handlerTestCommand.descriptor));
                }
            }
            if (actions.get().handlerFailedCommand != null) {
                final var runnable = actions.get().handlerFailedCommand.getKey();
                final var description = actions.get().handlerFailedCommand.getValue();
                final var postCommitEvent = new Camunda8TransactionProcessor.Camunda8CommandAfterTx(
                        methodSignature,
                        () -> runnable.accept(e),
                        null,
                        () -> description.apply(e));
                if (isTxActive) {
                    publisher.publishEvent(postCommitEvent);
                } else {
                   new Camunda8TransactionProcessor().processPostCommit(postCommitEvent);
                }
            }
            throw e;

        }

    }

    public static void clearCallbacks() {

        actions.get().bpmnErrorCommand = null;
        actions.get().handlerCompletedCommand = null;
        actions.get().handlerFailedCommand = null;
        actions.get().testForTaskAlreadyCompletedOrCancelledCommand = null;

    }

    private Object[] runDeferredInTransactionArgsSupplier(
            final Object[] originalArgs) {

        if (originalArgs == null) {
            return null;
        }

        final var suppliers = runDeferredInTransaction.get();
        // if activity currently executed is not part of C8 but
        // part of other adapters like (C7), then simply return
        if ((suppliers == null)
                || (suppliers.argsSupplier == null)) {
            return originalArgs;
        }

        final var newArgs = new Object[ originalArgs.length ];
        for (var i = 0; i < originalArgs.length; ++i) {
            final var supplier = suppliers.argsSupplier[i];
            if (supplier != null) {
                newArgs[i] = supplier.get();
            } else {
                newArgs[i] = originalArgs[i];
            }
        }

        return newArgs;

    }

}
