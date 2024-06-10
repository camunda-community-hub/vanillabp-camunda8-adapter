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

    public static class TaskHandlerActions {
        public Supplier<Map.Entry<Runnable, Supplier<String>>> testForTaskAlreadyCompletedOrCancelledCommand;
        public Map.Entry<Consumer<TaskException>, Function<TaskException, String>> bpmnErrorCommand;
        public Map.Entry<Consumer<Exception>, Function<Exception, String>> handlerFailedCommand;
        public Supplier<Map.Entry<Runnable, Supplier<String>>> handlerCompletedCommand;
    }

    public static final ThreadLocal<TaskHandlerActions> actions = ThreadLocal.withInitial(TaskHandlerActions::new);

    private final ApplicationEventPublisher publisher;

    public Camunda8TransactionAspect(
            final ApplicationEventPublisher publisher) {

        this.publisher = publisher;

    }

    @Around("@annotation(io.vanillabp.spi.service.WorkflowTask)")
    private Object checkForTransaction(
            final ProceedingJoinPoint pjp) throws Throwable {

        final var methodSignature = pjp.getSignature().toLongString();
        
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            clearCallbacks();
            logger.trace("Disable TX callbacks for {}: No TX active", methodSignature);
        }
        try {

            final var value = pjp.proceed();
            if (actions.get().testForTaskAlreadyCompletedOrCancelledCommand != null) {
                final var handlerTestCommand = actions.get().testForTaskAlreadyCompletedOrCancelledCommand.get();
                if (handlerTestCommand != null) {
                    publisher.publishEvent(
                            new Camunda8TransactionProcessor.Camunda8TestForTaskAlreadyCompletedOrCancelled(
                                    methodSignature,
                                    handlerTestCommand.getKey(),
                                    handlerTestCommand.getValue()));
                }
            }
            if (actions.get().handlerCompletedCommand != null) {
                final var handlerCompletedCommand = actions.get().handlerCompletedCommand.get();
                if (handlerCompletedCommand != null) {
                    publisher.publishEvent(
                            new Camunda8TransactionProcessor.Camunda8CommandAfterTx(
                                    methodSignature,
                                    handlerCompletedCommand.getKey(),
                                    handlerCompletedCommand.getValue()));
                }
            }
            return value;

        } catch (TaskException taskError) {

            if (actions.get().testForTaskAlreadyCompletedOrCancelledCommand != null) {
                final var handlerTestCommand = actions.get().testForTaskAlreadyCompletedOrCancelledCommand.get();
                if (handlerTestCommand != null) {
                    publisher.publishEvent(
                            new Camunda8TransactionProcessor.Camunda8TestForTaskAlreadyCompletedOrCancelled(
                                    methodSignature,
                                    handlerTestCommand.getKey(),
                                    handlerTestCommand.getValue()));
                }
            }
            if (actions.get().bpmnErrorCommand != null) {
                final var runnable = actions.get().handlerFailedCommand.getKey();
                final var description = actions.get().handlerFailedCommand.getValue();
                publisher.publishEvent(
                        new Camunda8TransactionProcessor.Camunda8CommandAfterTx(
                                methodSignature,
                                () -> runnable.accept(taskError),
                                () -> description.apply(taskError)));
            }
            return null;

        } catch (Exception e) {

            if (actions.get().testForTaskAlreadyCompletedOrCancelledCommand != null) {
                final var handlerTestCommand = actions.get().testForTaskAlreadyCompletedOrCancelledCommand.get();
                if (handlerTestCommand != null) {
                    publisher.publishEvent(
                            new Camunda8TransactionProcessor.Camunda8TestForTaskAlreadyCompletedOrCancelled(
                                    methodSignature,
                                    handlerTestCommand.getKey(),
                                    handlerTestCommand.getValue()));
                }
            }
            if (actions.get().handlerFailedCommand != null) {
                final var runnable = actions.get().handlerFailedCommand.getKey();
                final var description = actions.get().handlerFailedCommand.getValue();
                publisher.publishEvent(
                        new Camunda8TransactionProcessor.Camunda8CommandAfterTx(
                                methodSignature,
                                () -> runnable.accept(e),
                                () -> description.apply(e)));
            }
            throw e;

        } finally {

            clearCallbacks();

        }

    }

    public static void clearCallbacks() {

        actions.get().bpmnErrorCommand = null;
        actions.get().handlerCompletedCommand = null;
        actions.get().handlerFailedCommand = null;
        actions.get().testForTaskAlreadyCompletedOrCancelledCommand = null;

    }

}
