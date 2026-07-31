package io.vanillabp.camunda8.wiring;

import static org.assertj.core.api.Assertions.assertThat;

import io.camunda.client.api.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Pins down which of Camunda's three {@code JsonMapper} configurations actually wins, and it is not the
 * one the conditions suggest.
 *
 * <p>Measured against {@code camunda-spring-boot-4-starter:8.8.33} on Spring Boot 4.1: that starter pulls
 * in Jackson 3 ({@code tools.jackson.core:jackson-databind}) <b>and</b> Boot's {@code spring-boot-jackson}
 * auto-configuration transitively, so a Jackson 3 {@code ObjectMapper} bean named
 * {@code jacksonJsonMapper} is present. {@code Jackson3JsonMapperConfiguration} is
 * {@code @ConditionalOnClass} plus {@code @ConditionalOnBean} of exactly that type, so one would expect a
 * {@code CamundaJackson3ObjectMapper}.
 *
 * <p>What happens instead: the module-less {@code CamundaObjectMapper} from
 * {@code DefaultJsonMapperConfiguration} wins. The likely cause is ordering.
 * {@code DefaultJsonMapperConfiguration} is reached through a plain {@code @Import} from
 * {@code CamundaClientAllAutoConfiguration} and {@code CamundaClientProdAutoConfiguration}, so it is
 * processed as a regular configuration, before auto-configurations are evaluated. Its
 * {@code @ConditionalOnMissingBean} therefore sees no {@code JsonMapper} yet and creates the fallback;
 * {@code Jackson3JsonMapperConfiguration}, a real auto-configuration evaluated later, then backs off.
 *
 * <p><b>Consequence, and it is a functional gap rather than a curiosity:</b> on Spring Boot 4 the
 * application's Jackson 3 setup is ignored for Zeebe variables, and the mapper that is used registers no
 * modules at all - so workflow aggregates carrying {@code OffsetDateTime}, {@code Instant} or
 * {@code LocalDate} cannot be serialized. See
 * {@code AggregateWireFormatTest#withoutAnyJacksonSetupJavaTimeTypesCannotBeSerialised}.
 *
 * <p>The same effective result holds with {@code camunda-spring-boot-starter:8.9.13}, for a different
 * reason: that one does not bring Jackson 3 at all, so the fallback is the only candidate.
 *
 * <p>This test is a tripwire in the useful direction: should Camunda fix the ordering, it goes red and we
 * find out, instead of a mapper change slipping in unnoticed.
 */
@SpringBootTest(
        classes = JsonMapperSelectionDiagnosticTest.PlainApplication.class,
        properties = "spring.autoconfigure.exclude="
                + "io.vanillabp.camunda8.Camunda8AdapterConfiguration,"
                + "io.vanillabp.springboot.adapter.AdapterAwareProcessServiceConfiguration")
class JsonMapperSelectionDiagnosticTest {

    @SpringBootApplication
    static class PlainApplication {
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void aJackson3ObjectMapperBeanIsAvailable() {

        assertThat(applicationContext.getBeanNamesForType(tools.jackson.databind.ObjectMapper.class))
                .as("Boot's spring-boot-jackson auto-configuration should provide a Jackson 3 mapper")
                .isNotEmpty();

    }

    @Test
    void noJackson2ObjectMapperBeanIsAvailable() {

        // Boot 4 auto-configures Jackson 3 only; a Jackson 2 bean would require the separate
        // spring-boot-jackson2 compatibility module. This is why Camunda's JsonMapperConfiguration -
        // the case that copies a Jackson 2 mapper and inherits its modules - cannot apply either.
        assertThat(applicationContext.getBeanNamesForType(
                com.fasterxml.jackson.databind.ObjectMapper.class)).isEmpty();

    }

    @Test
    void camundaNeverthelessSelectsTheModuleLessFallback() {

        assertThat(jsonMapper.getClass().getName())
                .as("Camunda now honours the application's Jackson 3 mapper - re-check the wire format "
                        + "golden samples and whether date-bearing aggregates work")
                .isEqualTo("io.camunda.client.impl.CamundaObjectMapper");

    }

}
