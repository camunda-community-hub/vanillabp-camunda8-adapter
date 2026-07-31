package io.vanillabp.camunda8;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;

/**
 * {@link Camunda8AdapterConfiguration} carries {@code @EnableRetry}, so every application using this
 * adapter loads Spring Retry's infrastructure.
 *
 * <p>Spring Retry is <b>not</b> managed by the Spring Boot BOM and has no Spring 7 release: the newest
 * version, 2.0.13, is built against spring-context 6.2. Our BOM raises that to 7.0.8 at runtime, so the
 * combination is untested by its vendor. This test measures whether it still works instead of assuming
 * it does.
 *
 * <p>This is load-bearing, not decoration. The Business Cockpit's Camunda 8 adapter annotates more than
 * half a dozen methods with {@code @Retryable} plus {@code @Recover} - among them
 * {@code DeploymentService.addBpmn(..)} with {@code maxAttempts = 100} against optimistic locking
 * failures, and the user-task and workflow handlers. Those retries are what keeps concurrent deployments
 * and task updates from failing, so {@code @EnableRetry} cannot simply be dropped.
 *
 * <p>The test therefore covers the pattern that is actually used: {@code @Retryable} with
 * {@code @Backoff}, exception filtering via {@code retryFor}, and a {@code @Recover} method that takes
 * over once the attempts are exhausted.
 *
 * <p>Should this fail on a future Spring version, dropping Spring Retry is not an option. The path is
 * then a migration to the retry support built into Spring Framework 7
 * ({@code org.springframework.resilience.annotation.Retryable} plus {@code org.springframework.core.retry}) -
 * and it has to be checked whether that offers an equivalent of {@code @Recover}, which the cockpit
 * relies on.
 */
class SpringRetryUnderFramework7Test {

    static final AtomicInteger ATTEMPTS = new AtomicInteger();

    static final AtomicInteger RECOVERED = new AtomicInteger();

    public static class FlakyBean {

        /** Succeeds on the third attempt - mirrors the retry-until-it-works case. */
        @Retryable(
                retryFor = IllegalStateException.class,
                maxAttempts = 3,
                backoff = @Backoff(delay = 1))
        public String work() {
            if (ATTEMPTS.incrementAndGet() < 3) {
                throw new IllegalStateException("not yet");
            }
            return "done";
        }

        /** Never succeeds - exercises the @Recover path the cockpit depends on. */
        @Retryable(
                retryFor = IllegalStateException.class,
                maxAttempts = 2,
                backoff = @Backoff(delay = 1))
        public String alwaysFails() {
            ATTEMPTS.incrementAndGet();
            throw new IllegalStateException("always");
        }

        @Recover
        public String recover(final IllegalStateException exception) {
            RECOVERED.incrementAndGet();
            return "recovered";
        }

    }

    @Configuration
    @EnableRetry
    static class RetryEnabledConfiguration {

        @Bean
        FlakyBean flakyBean() {
            return new FlakyBean();
        }

    }

    @Test
    void enableRetryStillBootstrapsUnderSpringFramework7() {

        ATTEMPTS.set(0);
        RECOVERED.set(0);

        new ApplicationContextRunner()
                .withUserConfiguration(RetryEnabledConfiguration.class)
                .run(context -> {
                    assertThat(context)
                            .as("@EnableRetry no longer bootstraps - Spring Retry 2.0.13 is built "
                                    + "against Spring 6.2 and every adapter user would fail to start")
                            .hasNotFailed();

                    // proves the interceptor is actually wired, not just that the context came up
                    assertThat(context.getBean(FlakyBean.class).work()).isEqualTo("done");
                    assertThat(ATTEMPTS.get()).isEqualTo(3);
                });

    }

    @Test
    void theRecoverMethodTakesOverWhenAttemptsAreExhausted() {

        ATTEMPTS.set(0);
        RECOVERED.set(0);

        new ApplicationContextRunner()
                .withUserConfiguration(RetryEnabledConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    // the cockpit's DeploymentService and task handlers rely on @Recover being invoked
                    // instead of the exception escaping
                    assertThat(context.getBean(FlakyBean.class).alwaysFails()).isEqualTo("recovered");
                    assertThat(ATTEMPTS.get()).isEqualTo(2);
                    assertThat(RECOVERED.get()).isEqualTo(1);
                });

    }

}
