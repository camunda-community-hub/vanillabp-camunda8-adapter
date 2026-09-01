package io.vanillabp.camunda8.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.vanillabp.spi.service.WorkflowTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * Regression net for {@link Camunda8TransactionAspect} and {@link Camunda8TransactionProcessor}.
 * Written against Spring Boot 3 to document the current behaviour before the Spring Boot 4 migration.
 *
 * <p>This is the actual transaction mechanism of the Camunda 8 adapter: an AspectJ {@code @Around}
 * advice on every {@code @WorkflowTask} annotated method, which defers Zeebe commands to the
 * transaction lifecycle via {@code @TransactionalEventListener}. (The commented-out
 * {@code BeanFactoryPostProcessor} that would replace Spring's {@code TransactionInterceptor} is dead
 * code and plays no role.)
 *
 * <p>Both failure modes are silent:
 * <ul>
 * <li>the advice is not applied - the Zeebe command is sent immediately instead of on commit, which
 * looks fine until a rollback happens;</li>
 * <li>note that the advice method {@code checkForTransaction} is <b>private</b>. Spring AOP discovers
 * non-public advice methods today, but that is an implementation detail worth pinning down.</li>
 * </ul>
 */
@SpringJUnitConfig(Camunda8TransactionAspectTest.TestConfiguration.class)
class Camunda8TransactionAspectTest {

    @Autowired
    private WorkflowTaskBean bean;

    @AfterEach
    void resetThreadLocals() {
        Camunda8TransactionAspect.actions.remove();
        Camunda8TransactionAspect.runDeferredInTransaction.remove();
    }


    /** Minimal transaction manager: no resources, but full synchronization support. */
    static class NoopTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(final Object transaction, final TransactionDefinition definition) {
            // nothing to bind
        }

        @Override
        protected void doCommit(final DefaultTransactionStatus status) {
            // nothing to commit
        }

        @Override
        protected void doRollback(final DefaultTransactionStatus status) {
            // nothing to roll back
        }

    }

    public static class WorkflowTaskBean {

        final AtomicInteger invocations = new AtomicInteger();

        @WorkflowTask
        public String withoutTransaction() {
            invocations.incrementAndGet();
            return "done";
        }

        @Transactional
        @WorkflowTask
        public String withTransaction() {
            invocations.incrementAndGet();
            return "done";
        }

        public String notAWorkflowTask() {
            invocations.incrementAndGet();
            return "done";
        }

    }

    @Configuration
    @EnableAspectJAutoProxy
    @EnableTransactionManagement
    static class TestConfiguration {

        @Bean
        PlatformTransactionManager transactionManager() {
            return new NoopTransactionManager();
        }

        @Bean
        Camunda8TransactionAspect camunda8TransactionAspect(final ApplicationEventPublisher publisher) {
            return new Camunda8TransactionAspect(publisher);
        }

        @Bean
        Camunda8TransactionProcessor camunda8TransactionProcessor() {
            return new Camunda8TransactionProcessor();
        }

        @Bean
        WorkflowTaskBean workflowTaskBean() {
            return new WorkflowTaskBean();
        }

    }

    @Test
    void theWorkflowTaskBeanIsProxied() {

        assertThat(AopUtils.isAopProxy(bean))
                .as("without a proxy the @Around advice can never run")
                .isTrue();

    }

    @Test
    void theAdviceRunsAndExecutesTheCommandImmediatelyWithoutATransaction() {

        final var commandExecuted = new AtomicBoolean();
        registerCompletedCommand(commandExecuted);

        final var result = bean.withoutTransaction();

        assertThat(result).isEqualTo("done");
        // note: bean.invocations must not be read through the injected reference - the bean is a CGLIB
        // proxy and its fields are not the target's fields. That is exactly why the aspect's effect is
        // asserted through the command below instead of through the target's state.
        // no transaction active -> the aspect executes the command right away
        assertThat(commandExecuted)
                .as("the @Around advice did not run - the Zeebe command was never executed")
                .isTrue();

    }

    @Test
    void withATransactionTheCommandIsDeferredUntilAfterCommit() {

        final var commandExecuted = new AtomicBoolean();
        registerCompletedCommand(commandExecuted);

        bean.withTransaction();

        // the command is published as an event and executed by the AFTER_COMMIT listener; by the
        // time the method returned, the transaction has been committed
        assertThat(commandExecuted)
                .as("the AFTER_COMMIT TransactionalEventListener did not fire")
                .isTrue();

    }

    @Test
    void methodsWithoutTheAnnotationAreNotAdvised() {

        final var commandExecuted = new AtomicBoolean();
        registerCompletedCommand(commandExecuted);

        bean.notAWorkflowTask();

        assertThat(commandExecuted)
                .as("the pointcut is too wide - it matches methods without @WorkflowTask")
                .isFalse();

    }

    private void registerCompletedCommand(final AtomicBoolean flag) {

        final var command = new Camunda8TransactionAspect.CommandWithFallback();
        command.command = () -> flag.set(true);
        command.fallback = null;
        command.descriptor = () -> "test-command";
        Camunda8TransactionAspect.actions.get().handlerCompletedCommand = () -> command;

    }

}
