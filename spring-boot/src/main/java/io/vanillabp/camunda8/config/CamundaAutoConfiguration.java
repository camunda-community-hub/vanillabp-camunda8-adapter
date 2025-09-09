package io.vanillabp.camunda8.config;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.worker.BackoffSupplier;
import io.camunda.spring.client.configuration.CamundaClientProdAutoConfiguration;
import io.camunda.spring.client.event.CamundaLifecycleEventProducer;
import io.camunda.spring.client.jobhandling.CamundaClientExecutorService;
import io.camunda.spring.client.jobhandling.CommandExceptionHandlingStrategy;
import io.camunda.spring.client.jobhandling.DefaultCommandExceptionHandlingStrategy;
import io.camunda.spring.client.properties.CamundaClientProperties;
import io.camunda.spring.client.testsupport.CamundaSpringProcessTestContext;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

/**
 * Everything necessary to get a CamundaClient but no {@link io.camunda.spring.client.annotation.JobWorker}
 * and {@link io.camunda.spring.client.annotation.Deployment} processing.
 */
@ImportAutoConfiguration({ CamundaClientProdAutoConfiguration.class })
public class CamundaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(
            CamundaSpringProcessTestContext
                    .class) // only run if we are not running in a test case - as otherwise the lifecycle
    // is controlled by the test
    public CamundaLifecycleEventProducer camundaLifecycleEventProducer(
            final CamundaClient client, final ApplicationEventPublisher publisher) {
        return new CamundaLifecycleEventProducer(client, publisher);
    }

    @Bean
    @ConditionalOnMissingBean
    public CamundaClientExecutorService camundaClientExecutorService(
            final CamundaClientProperties camundaClientProperties) {
        return CamundaClientExecutorService.createDefault(
                camundaClientProperties.getExecutionThreads());
    }

    @Bean
    @ConditionalOnMissingBean
    public CommandExceptionHandlingStrategy commandExceptionHandlingStrategy(
            final CamundaClientExecutorService scheduledExecutorService) {
        return new DefaultCommandExceptionHandlingStrategy(
                backoffSupplier(), scheduledExecutorService.get());
    }

    @Bean
    @ConditionalOnMissingBean
    public BackoffSupplier backoffSupplier() {
        return BackoffSupplier.newBackoffBuilder().build();
    }

}
