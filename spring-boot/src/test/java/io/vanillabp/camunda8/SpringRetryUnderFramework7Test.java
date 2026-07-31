package io.vanillabp.camunda8;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
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
 * <p>Note that the adapter itself declares no {@code @Retryable} anywhere - the retry infrastructure is
 * enabled but never consumed by this library. It only has an effect on consumers that annotate their own
 * beans. Should this test fail on a future Spring version, the cheapest fix is to drop
 * {@code @EnableRetry} and the Spring Retry dependency altogether; the more thorough one is to move to
 * the retry support built into Spring Framework 7 ({@code org.springframework.resilience.annotation.Retryable}
 * plus {@code org.springframework.core.retry}).
 */
class SpringRetryUnderFramework7Test {

    static final AtomicInteger ATTEMPTS = new AtomicInteger();

    public static class FlakyBean {

        @Retryable(maxAttempts = 3)
        public String work() {
            if (ATTEMPTS.incrementAndGet() < 3) {
                throw new IllegalStateException("not yet");
            }
            return "done";
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

}
