package io.vanillabp.camunda8.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Documents what {@link DisableCamundaSpringAutoConfigurationImportFilter} actually achieves in a running
 * context - which, measured against {@code camunda-spring-boot-starter:8.9.13}, is <b>nothing</b>.
 *
 * <p>The filter targets {@code io.camunda.client.spring.configuration.AnnotationProcessorConfiguration}.
 * An {@code AutoConfigurationImportFilter} can only remove auto-configuration <em>candidates</em>, and
 * that class is not one:
 *
 * <pre>
 * camunda-spring-boot-starter/META-INF/spring/...AutoConfiguration.imports
 *   -> io.camunda.client.spring.configuration.CamundaAutoConfiguration   (the only entry)
 *
 * CamundaAutoConfiguration
 *   -@ImportAutoConfiguration-> CamundaClientAllAutoConfiguration        (candidate, filterable)
 *        -@Import-> AnnotationProcessorConfiguration                     (NOT filterable)
 * </pre>
 *
 * Plain {@code @Import} is not subject to auto-configuration import filters, so the class arrives in the
 * context regardless. Verified for the previously used {@code camunda-spring-boot-3-starter:8.9.11} as
 * well - its {@code .imports} file has the identical single entry. The filter was therefore already a
 * no-op before the starter swap in T09; this is not a Spring Boot 4 regression. Most likely it did work
 * when it was written, against an older Zeebe Spring client, and silently stopped when Camunda
 * restructured its configuration classes.
 *
 * <p>Why nothing breaks anyway: the beans in {@code AnnotationProcessorConfiguration} only act on
 * Camunda's own annotations - {@code @JobWorker}, {@code @Deployment}, cluster variables. VanillaBP uses
 * its own {@code @WorkflowTask} and does its deployment through {@code Camunda8DeploymentAdapter}, so the
 * processors find nothing to process. Two of the three beans additionally sit behind
 * {@code @ConditionalOnProperty} ({@code camunda.client.deployment.enabled},
 * {@code camunda.client.cluster-variables.enabled}, both {@code matchIfMissing = true}), which is the
 * switch Camunda intends for turning them off.
 *
 * <p>The filter is kept rather than deleted: it costs nothing at runtime and remains a guard should
 * Camunda make that class a candidate again. What must not happen is trusting it - hence this test.
 * If the first assertion ever starts failing, the filter has become effective and the situation needs
 * re-assessing.
 */
/*
 * Both VanillaBP auto-configurations are excluded: they are registered through .imports files and would
 * be applied to any application on the classpath, then fail on ${workerId} and ${spring.application.name}
 * respectively. Neither is the subject of this test. The import filter under test is registered globally
 * through spring.factories, independently of these exclusions.
 *
 * That the exclusions are needed at all is a useful side observation: it proves @AutoConfiguration on
 * Camunda8AdapterConfiguration is in effect and that the .imports registration of both libraries works.
 */
@SpringBootTest(
        classes = ImportFilterTakesEffectTest.PlainApplication.class,
        properties = "spring.autoconfigure.exclude="
                + "io.vanillabp.camunda8.Camunda8AdapterConfiguration,"
                + "io.vanillabp.springboot.adapter.AdapterAwareProcessServiceConfiguration")
class ImportFilterTakesEffectTest {

    private static final String FILTER_TARGET =
            "io.camunda.client.spring.configuration.AnnotationProcessorConfiguration";

    private static final String FILTERABLE_PARENT =
            "io.camunda.client.spring.configuration.CamundaClientAllAutoConfiguration";

    @SpringBootApplication
    static class PlainApplication {
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void theFilterTargetReachesTheContextRegardless() {

        // measured, not assumed: the filter does not suppress its target, because the target is reached
        // through a plain @Import rather than as an auto-configuration candidate
        assertThat(applicationContext.containsBeanDefinition(FILTER_TARGET))
                .as("the filter has become effective - re-assess whether suppressing Camunda's "
                        + "annotation processing is still wanted and whether it is now complete")
                .isTrue();

    }

    @Test
    void theOnlyFilterableAncestorIsTheAllAutoConfiguration() {

        // if Camunda's annotation processing ever has to be suppressed for real, this is the class an
        // import filter could remove - at the price of also losing the bean post processor and the JSON
        // mapper configuration it imports. A property is the better switch.
        assertThat(applicationContext.containsBeanDefinition(FILTERABLE_PARENT)).isTrue();

    }

}
