package io.vanillabp.camunda8.service;

import io.vanillabp.spi.service.TaskException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class NotUsedCamunda8TransactionInterceptor extends TransactionInterceptor {

    private final ApplicationEventPublisher publisher;

    public static final ThreadLocal<TaskHandlerActions> actions = ThreadLocal.withInitial(TaskHandlerActions::new);

    public NotUsedCamunda8TransactionInterceptor(
            final TransactionManager ptm,
            final TransactionAttributeSource tas,
            final ApplicationEventPublisher publisher) {
        super(ptm, tas);
        this.publisher = publisher;
    }

    public static class TaskHandlerActions {
        public Map.Entry<Runnable, Supplier<String>> testForTaskAlreadyCompletedOrCancelledCommand;
        public Map.Entry<Consumer<TaskException>, Function<TaskException, String>> bpmnErrorCommand;
        public Map.Entry<Consumer<Exception>, Function<Exception, String>> handlerFailedCommand;
        public Supplier<Map.Entry<Runnable, Supplier<String>>> handlerCompletedCommand;
    }

    @Override
    protected Object invokeWithinTransaction(
            final Method method,
            final Class<?> targetClass,
            final InvocationCallback invocation) throws Throwable {

        return super.invokeWithinTransaction(method, targetClass, () -> {
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                return invocation.proceedWithInvocation();
            }
            try {
                logger.info("Before TX");
                final var result = invocation.proceedWithInvocation();
                logger.info("After TX");
                if (actions.get().testForTaskAlreadyCompletedOrCancelledCommand != null) {
                    publisher.publishEvent(
                            new Camunda8TransactionProcessor.Camunda8TestForTaskAlreadyCompletedOrCancelled(
                                    NotUsedCamunda8TransactionInterceptor.class,
                                    actions.get().testForTaskAlreadyCompletedOrCancelledCommand.getKey(),
                                    actions.get().testForTaskAlreadyCompletedOrCancelledCommand.getValue()));
                }
                if (actions.get().handlerCompletedCommand != null) {
                    final var handlerCompletedCommand = actions.get().handlerCompletedCommand.get();
                    if (handlerCompletedCommand != null) {
                        publisher.publishEvent(
                                new Camunda8TransactionProcessor.Camunda8CommandAfterTx(
                                        NotUsedCamunda8TransactionInterceptor.class,
                                        handlerCompletedCommand.getKey(),
                                        handlerCompletedCommand.getValue()));
                    }
                }
                return result;
            } catch (TaskException taskError) {
                if (actions.get().testForTaskAlreadyCompletedOrCancelledCommand != null) {
                    publisher.publishEvent(
                            new Camunda8TransactionProcessor.Camunda8TestForTaskAlreadyCompletedOrCancelled(
                                    NotUsedCamunda8TransactionInterceptor.class,
                                    actions.get().testForTaskAlreadyCompletedOrCancelledCommand.getKey(),
                                    actions.get().testForTaskAlreadyCompletedOrCancelledCommand.getValue()));
                }
                if (actions.get().bpmnErrorCommand != null) {
                    publisher.publishEvent(
                            new Camunda8TransactionProcessor.Camunda8CommandAfterTx(
                                    NotUsedCamunda8TransactionInterceptor.class,
                                    () -> actions.get().bpmnErrorCommand.getKey().accept(taskError),
                                    () -> actions.get().bpmnErrorCommand.getValue().apply(taskError)));
                }
                return null;
            } catch (Exception e) {
                if (actions.get().testForTaskAlreadyCompletedOrCancelledCommand != null) {
                    publisher.publishEvent(
                            new Camunda8TransactionProcessor.Camunda8TestForTaskAlreadyCompletedOrCancelled(
                                    NotUsedCamunda8TransactionInterceptor.class,
                                    actions.get().testForTaskAlreadyCompletedOrCancelledCommand.getKey(),
                                    actions.get().testForTaskAlreadyCompletedOrCancelledCommand.getValue()));
                }
                if (actions.get().handlerFailedCommand != null) {
                    publisher.publishEvent(
                            new Camunda8TransactionProcessor.Camunda8CommandAfterTx(
                                    NotUsedCamunda8TransactionInterceptor.class,
                                    () -> actions.get().handlerFailedCommand.getKey().accept(e),
                                    () -> actions.get().handlerFailedCommand.getValue().apply(e)));
                }
                throw e;
            } finally {
                actions.get().bpmnErrorCommand = null;
                actions.get().handlerCompletedCommand = null;
                actions.get().handlerFailedCommand = null;
                actions.get().testForTaskAlreadyCompletedOrCancelledCommand = null;
            }
        });

    }

}
