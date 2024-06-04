package io.vanillabp.camunda8.service;

import io.camunda.zeebe.client.api.command.ClientStatusException;
import io.grpc.Status;
import io.vanillabp.spi.service.TaskException;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEvent;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class Camunda8TransactionProcessor {

    private static final Logger logger = LoggerFactory.getLogger(Camunda8TransactionProcessor.class);

    public static void registerCallbacks(
            final Supplier<Map.Entry<Runnable, Supplier<String>>> testForTaskAlreadyCompletedOrCancelledCommand,
            final Map.Entry<Consumer<TaskException>, Function<TaskException, String>> bpmnErrorCommand,
            final Map.Entry<Consumer<Exception>, Function<Exception, String>> handlerFailedCommand,
            final Supplier<Map.Entry<Runnable, Supplier<String>>> handlerCompletedCommand) {

        final var actions = Camunda8TransactionAspect.actions.get();
        actions.testForTaskAlreadyCompletedOrCancelledCommand = testForTaskAlreadyCompletedOrCancelledCommand;
        actions.bpmnErrorCommand = bpmnErrorCommand;
        actions.handlerFailedCommand = handlerFailedCommand;
        actions.handlerCompletedCommand = handlerCompletedCommand;

    }

    public static Map.Entry<Consumer<TaskException>, Function<TaskException, String>> bpmnErrorCommandCallback() {

        return Camunda8TransactionAspect
                .actions
                .get()
                .bpmnErrorCommand;

    }

    public static Map.Entry<Consumer<Exception>, Function<Exception, String>> handlerFailedCommandCallback() {

        return Camunda8TransactionAspect
                .actions
                .get()
                .handlerFailedCommand;

    }

    public static Map.Entry<Runnable, Supplier<String>> handlerCompletedCommandCallback() {

        return Camunda8TransactionAspect
                .actions
                .get()
                .handlerCompletedCommand
                .get();

    }

    public static void unregisterCallbacks() {

        final var actions = Camunda8TransactionAspect.actions.get();
        actions.testForTaskAlreadyCompletedOrCancelledCommand = null;
        actions.bpmnErrorCommand = null;
        actions.handlerFailedCommand = null;
        actions.handlerCompletedCommand = null;

    }

    public static class Camunda8CommandAfterTx extends ApplicationEvent {
        final Supplier<String> description;
        final Runnable runnable;
        public Camunda8CommandAfterTx(
                final Object source,
                final Runnable runnable,
                final Supplier<String> description) {
            super(source);
            this.runnable = runnable;
            this.description = description;
        }
    }

    public static class Camunda8TestForTaskAlreadyCompletedOrCancelled extends ApplicationEvent {
        final Supplier<String> description;
        final Runnable runnable;
        public Camunda8TestForTaskAlreadyCompletedOrCancelled(
                final Object source,
                final Runnable runnable,
                final Supplier<String> description) {
            super(source);
            this.runnable = runnable;
            this.description = description;
        }
    }

    @TransactionalEventListener(
            phase = TransactionPhase.BEFORE_COMMIT,
            fallbackExecution = true)
    public void processPreCommit(
            final Camunda8TestForTaskAlreadyCompletedOrCancelled event) {

        try {
            logger.trace("Will test for existence of task '{}' initiated by: {}",
                    event.description.get(),
                    event.getSource());
            // this runnable will test whether the task still exists
            event.runnable.run();
        } catch (Exception e) {
            // if the task is completed or cancelled, then the tx is rolled back
            if ((e instanceof ClientStatusException clientStatusException)
                    && (clientStatusException.getStatus().getCode() == Status.NOT_FOUND.getCode())) {
                logger.warn(
                        "Will rollback because job was already completed/cancelled! Tested with command '{}‘ giving status 'NOT_FOUND'",
                        event.description.get());
            } else {
                logger.warn(
                        "Will rollback because testing for job '{}' failed!",
                        event.description.get(),
                        e);
            }
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
        }

    }

    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true)
    public void processPostCommit(
            final Camunda8CommandAfterTx event) {

        try {
            logger.trace("Will execute Camunda command for '{}' initiated by: {}",
                    event.description.get(),
                    event.getSource());
            // this runnable will instruct Zeebe
            event.runnable.run();
        } catch (Exception e) {
            logger.error(
                    "Could not execute camunda command for '{}'! Manual action required!",
                    event.description.get(),
                    e);
        }

    }

}
