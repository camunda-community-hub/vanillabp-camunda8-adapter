package io.vanillabp.camunda8.wiring;

import static org.assertj.core.api.Assertions.assertThat;

import io.camunda.client.api.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Which {@code JsonMapper} Camunda selects when this adapter does <b>not</b> provide one - the adapter's own
 * auto-configuration is excluded below. Since {@code Camunda8AdapterConfiguration} contributes a
 * {@link io.vanillabp.camunda8.Camunda8Jackson3JsonMapper}, production no longer runs into what this test
 * describes; it is kept because it pins down Camunda's behaviour, which is what the bridge has to keep
 * compensating for.
 *
 * <p>Measured against {@code camunda-spring-boot-4-starter:8.8.33} on Spring Boot 4.1. That starter ships a
 * single {@code io.camunda.client.spring.configuration.JsonMapperConfiguration}, read from its byte code:
 * its constructor takes a <b>Jackson 2</b> {@code com.fasterxml.jackson.databind.ObjectMapper} with
 * {@code @Autowired(required = false)}, and its {@code jsonMapper()} bean method is
 * {@code @ConditionalOnMissingBean}. It creates {@code new CamundaObjectMapper(objectMapper)} when that
 * parameter is set and {@code new CamundaObjectMapper()} otherwise.
 *
 * <p>On Spring Boot 4 the parameter is always {@code null}: Boot auto-configures Jackson <b>3</b>, and a
 * Jackson 2 {@code ObjectMapper} bean would require the separate {@code spring-boot-jackson2} module. So the
 * module-less fallback wins - and it wins regardless of ordering or of the fact that a perfectly good
 * Jackson 3 mapper is sitting in the same context. This starter has no Jackson 3 code path at all; the
 * three-configuration selection (Jackson 3 / Jackson 2 / fallback) only exists from 8.9 on.
 *
 * <p><b>Consequence, and it is a functional gap rather than a curiosity:</b> the mapper that is used
 * registers no modules, so workflow aggregates carrying {@code OffsetDateTime}, {@code Instant} or
 * {@code LocalDate} cannot be serialized. See
 * {@link AggregateWireFormatTest#withoutAnyJacksonSetupJavaTimeTypesCannotBeSerialised()} for the failure
 * and {@link JsonMapperPrecedenceTest} for the bridge that closes it.
 *
 * <p>This test is a tripwire in the useful direction: should Camunda start honouring Jackson 3 - by
 * upgrading to 8.9 or by backporting - it goes red and we find out, instead of a mapper change slipping in
 * unnoticed.
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
