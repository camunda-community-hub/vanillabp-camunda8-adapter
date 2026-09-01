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
 * <p>Both VanillaBP auto-configurations are excluded below, so what is measured here is <b>Camunda's</b>
 * choice of mapper, not the adapter's. With {@code camunda-spring-boot-4-starter:8.8.33} that is always the
 * module-less {@code CamundaObjectMapper}: Camunda's {@code JsonMapperConfiguration} only knows how to take
 * a <b>Jackson 2</b> {@code ObjectMapper} bean, and Spring Boot 4 auto-configures Jackson <b>3</b>. The
 * reasoning is in {@link JsonMapperSelectionDiagnosticTest}.
 *
 * <p>Note that Jackson 3 <i>is</i> on this module's classpath - it arrives with the starter, together with
 * Boot's {@code spring-boot-jackson} auto-configuration - it is simply unreachable for Camunda. What is
 * absent is {@code jackson-datatype-jsr310}, the Jackson 2 module for the Java 8 date and time types, which
 * is why the fallback mapper cannot serialize them.
 *
 * <p>Since {@code Camunda8AdapterConfiguration} now contributes a
 * {@link io.vanillabp.camunda8.Camunda8Jackson3JsonMapper}, an application using this adapter gets the
 * application's Jackson 3 setup instead. The samples below still matter: they are the format the bridge
 * deliberately reproduces, so that only date handling changes. Its counterpart is
 * {@code io.vanillabp.camunda8.Camunda8Jackson3JsonMapperTest}, which asserts the same strings.
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
