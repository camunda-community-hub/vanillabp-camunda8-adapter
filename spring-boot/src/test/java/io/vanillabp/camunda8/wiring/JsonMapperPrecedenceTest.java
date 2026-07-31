package io.vanillabp.camunda8.wiring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.JsonMapper;
import io.camunda.client.spring.configuration.JsonMapperConfiguration;
import io.vanillabp.camunda8.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.Camunda8Jackson3JsonMapper;
import io.vanillabp.springboot.adapter.SpringDataUtil;
import io.vanillabp.springboot.adapter.VanillaBpProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Whose {@link JsonMapper} ends up in the context - ours or Camunda's fallback.
 *
 * <p>The claim being tested is an ordering claim, and ordering claims are exactly the kind that quietly
 * stop holding: {@code Camunda8AdapterConfiguration} is
 * {@code @AutoConfiguration(before = CamundaAutoConfiguration.class)}, and Camunda's
 * {@code JsonMapperConfiguration} is named there too. The second part is not decoration: Camunda pulls
 * that class in with {@code @ImportAutoConfiguration}, so it is an auto-configuration of its own and
 * ordering against {@code CamundaAutoConfiguration} alone would say nothing about it. If Camunda renames or
 * relocates it, this test goes red - otherwise the module-less fallback would silently win again and
 * date-bearing workflow aggregates would stop working, see {@link JsonMapperSelectionDiagnosticTest}.
 *
 * <p>What is registered here is the pair whose order decides the outcome: this adapter's configuration and
 * Camunda's {@code JsonMapperConfiguration}, both as auto-configurations, so Boot's real ordering machinery
 * runs. {@code CamundaAutoConfiguration} itself is deliberately left out - it starts the client, which asks
 * a cluster for its topology, and its {@code camundaClient} bean method carries no
 * {@code @ConditionalOnMissingBean}, so it cannot be stubbed out by name either. The end-to-end path with a
 * real cluster belongs to the blueprints.
 */
class JsonMapperPrecedenceTest {

    /**
     * The adapter configuration injects {@code SpringDataUtil} to make sure persistence is up before any
     * process is deployed, and reads {@code workerId} from the environment.
     */
    @Configuration
    static class MinimalAdapterEnvironment {

        @Bean
        SpringDataUtil springDataUtil() {
            return mock(SpringDataUtil.class);
        }

        @Bean
        VanillaBpProperties vanillaBpProperties() {
            return new VanillaBpProperties();
        }

        /*
         * Several of the adapter's beans take the client. Nothing talks to it in this test.
         */
        @Bean
        CamundaClient camundaClient() {
            return mock(CamundaClient.class, RETURNS_DEEP_STUBS);
        }

    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withPropertyValues(
                    "workerId=test-worker",
                    "spring.application.name=test-module",
                    "vanillabp.workflow-modules.test-module.adapters.camunda8.resources-location"
                            + "=classpath*:/no-such-processes/")
            .withUserConfiguration(MinimalAdapterEnvironment.class)
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class,
                    Camunda8AdapterConfiguration.class,
                    JsonMapperConfiguration.class));

    @Test
    void ourBridgeWinsOverCamundasFallback() {

        runner.run(context -> assertThat(context.getBean(JsonMapper.class))
                .as("Camunda's module-less fallback is in use - date-bearing aggregates cannot be sent")
                .isInstanceOf(Camunda8Jackson3JsonMapper.class));

    }

    @Test
    void anApplicationsOwnJsonMapperStillWins() {

        final var applicationMapper = mock(JsonMapper.class);

        runner.withBean("applicationJsonMapper", JsonMapper.class, () -> applicationMapper)
                .run(context -> assertThat(context.getBean(JsonMapper.class)).isSameAs(applicationMapper));

    }

}
