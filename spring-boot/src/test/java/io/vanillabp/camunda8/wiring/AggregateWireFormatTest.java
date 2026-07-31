package io.vanillabp.camunda8.wiring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.camunda.client.api.JsonMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Golden samples of the JSON that goes to Zeebe as process variables, taken from the
 * <b>auto-configured</b> {@code io.camunda.client.api.JsonMapper} bean rather than a hand-built mapper -
 * that is the one production uses. {@code Camunda8AdapterConfiguration} injects it and hands it to
 * {@code Camunda8ProcessService}, which serializes both workflow aggregates and aggregate ids with it.
 *
 * <p><b>Which mapper is selected depends on the application, not on this library.</b> Camunda offers
 * three configurations, and the conditions were read from the byte code of
 * {@code camunda-spring-boot-starter:8.9.13}:
 *
 * <ol>
 * <li>{@code Jackson3JsonMapperConfiguration} - {@code @ConditionalOnClass} and
 * {@code @ConditionalOnBean} of {@code tools.jackson.databind.ObjectMapper}. Wraps the application's
 * Jackson 3 mapper in a {@code CamundaJackson3ObjectMapper}. This is what applications on Spring Boot 4
 * with a web or JSON starter get, and Jackson 3 has the Java 8 date/time types built in.</li>
 * <li>{@code JsonMapperConfiguration} - takes the Spring-provided Jackson 2
 * {@code com.fasterxml.jackson.databind.ObjectMapper} bean and {@code copy()}s it, so it inherits
 * whatever modules Spring registered. Boot 4 still offers such a bean through its
 * {@code spring-boot-jackson2} compatibility module.</li>
 * <li>{@code DefaultJsonMapperConfiguration} - {@code @ConditionalOnMissingBean} fallback: a bare
 * {@code CamundaObjectMapper} with a plain Jackson 2 {@code ObjectMapper} and <b>no</b> modules.</li>
 * </ol>
 *
 * <p>This library module is not an application: it has neither Jackson 3 nor a Jackson 2
 * {@code ObjectMapper} bean nor {@code jackson-datatype-jsr310} on its classpath, so case 3 applies here.
 * The tests below therefore pin down what that fallback produces - which is exactly the configuration an
 * application without any Jackson setup would get, so it is worth knowing.
 *
 * <p>The end-to-end wire format of a date-bearing aggregate can only be pinned down at application level,
 * where Jackson 3 is present. That belongs to T20/T21 together with the running Zeebe.
 */
/*
 * Both VanillaBP auto-configurations are excluded: they would be applied to any application on the
 * classpath and fail on ${workerId} and ${spring.application.name}. Only Camunda's JsonMapper
 * auto-configuration is of interest here.
 */
@SpringBootTest(
        classes = AggregateWireFormatTest.PlainApplication.class,
        properties = "spring.autoconfigure.exclude="
                + "io.vanillabp.camunda8.Camunda8AdapterConfiguration,"
                + "io.vanillabp.springboot.adapter.AdapterAwareProcessServiceConfiguration")
class AggregateWireFormatTest {

    @SpringBootApplication
    static class PlainApplication {
    }

    @Autowired
    private JsonMapper jsonMapper;

    /** Value types that are format-stable regardless of the registered Jackson modules. */
    public static class Aggregate {

        public String id;

        public BigDecimal amount;

        public String comment;

        public boolean approved;

        public int retries;

    }

    /** Only this one carries a Java 8 date type. */
    public static class AggregateWithDate {

        public String id;

        public OffsetDateTime dueDate;

    }

    private Aggregate aggregate() {

        final var aggregate = new Aggregate();
        aggregate.id = "loan-4711";
        aggregate.amount = new BigDecimal("1234.50");
        aggregate.comment = null;
        aggregate.approved = true;
        aggregate.retries = 0;
        return aggregate;

    }

    @Test
    void theFallbackMapperIsSelectedInThisModule() {

        // documents case 3 of the three-way selection described above
        assertThat(jsonMapper.getClass().getName())
                .isEqualTo("io.camunda.client.impl.CamundaObjectMapper");

    }

    @Test
    void theAggregateWireFormatIsStable() {

        final var json = jsonMapper.toJson(aggregate());

        assertThat(json).isEqualTo("{\"id\":\"loan-4711\",\"amount\":1234.50,\"comment\":null,"
                + "\"approved\":true,\"retries\":0}");

    }

    @Test
    void bigDecimalScaleIsPreserved() {

        // 1234.50, not 1234.5 - the trailing zero is part of the value for monetary amounts
        assertThat(jsonMapper.toJson(aggregate())).contains("\"amount\":1234.50");

    }

    @Test
    void nullPropertiesAreWrittenRatherThanOmitted() {

        // Measured, and the opposite of the cockpit's Kafka mapper, which is configured with NON_NULL:
        // this mapper writes nulls explicitly. That matters for Zeebe, where an absent variable and a
        // null variable are not the same thing - a variable set to null exists and overwrites a previous
        // value, whereas an omitted one leaves it untouched.
        assertThat(jsonMapper.toJson(aggregate())).contains("\"comment\":null");

    }

    @Test
    void zeroValuedPrimitivesAreWrittenRatherThanOmitted() {

        assertThat(jsonMapper.toJson(aggregate())).contains("\"retries\":0");

    }

    @Test
    void theAggregateRoundTrips() {

        final var json = jsonMapper.toJson(aggregate());

        final var readBack = jsonMapper.fromJson(json, Aggregate.class);

        assertThat(readBack.id).isEqualTo("loan-4711");
        assertThat(readBack.amount).isEqualByComparingTo("1234.50");
        assertThat(readBack.approved).isTrue();
        assertThat(readBack.retries).isZero();
        assertThat(readBack.comment).isNull();

    }

    @Test
    void idTypesSerialiseAsExpected() {

        // Camunda8ProcessService serializes the aggregate id separately, for correlation
        assertThat(jsonMapper.toJson("loan-4711")).isEqualTo("\"loan-4711\"");
        assertThat(jsonMapper.toJson(4711)).isEqualTo("4711");
        assertThat(jsonMapper.toJson(UUID.fromString("00000000-0000-0000-0000-00000000002a")))
                .isEqualTo("\"00000000-0000-0000-0000-00000000002a\"");

    }

    @Test
    void withoutAnyJacksonSetupJavaTimeTypesCannotBeSerialised() {

        final var aggregate = new AggregateWithDate();
        aggregate.id = "loan-4711";
        aggregate.dueDate = OffsetDateTime.of(2026, 7, 31, 12, 34, 56, 0, ZoneOffset.ofHours(2));

        // Measured, and a real constraint rather than a test artefact: the fallback mapper registers no
        // modules at all. An application that provides neither a Jackson 3 mapper nor a Jackson 2
        // ObjectMapper bean cannot send workflow aggregates carrying OffsetDateTime, Instant or
        // LocalDate. Applications with a web or JSON starter are fine - they hit case 1 or 2.
        // the client wraps serialization problems, so the Jackson detail is in the cause
        assertThatThrownBy(() -> jsonMapper.toJson(aggregate))
                .isInstanceOf(io.camunda.client.api.command.InternalClientException.class)
                .hasMessageContaining("Failed to serialize object")
                .rootCause()
                .hasMessageContaining("Java 8 date/time type")
                .hasMessageContaining("not supported by default");

    }

}
