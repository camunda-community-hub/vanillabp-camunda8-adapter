package io.vanillabp.camunda8;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.camunda.client.api.command.InternalClientException;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wire format of the Zeebe process variables produced by {@link Camunda8Jackson3JsonMapper}, plus the
 * error contract it inherits from {@code CamundaObjectMapper}.
 *
 * <p>The mapper handed in here is a plain {@code JsonMapper.builder().build()}, which is what an
 * application without any {@code spring.jackson.*} configuration gets from Boot's auto-configuration.
 * Applications that customise Jackson get their settings applied to Zeebe variables as well - that is the
 * point of the bridge - so these samples are the baseline, not a guarantee for every application.
 *
 * <p>The date assertions are the reason this class exists: with Camunda's own fallback mapper the very
 * same aggregate cannot be serialized at all, see
 * {@link io.vanillabp.camunda8.wiring.AggregateWireFormatTest#withoutAnyJacksonSetupJavaTimeTypesCannotBeSerialised()}.
 */
class Camunda8Jackson3JsonMapperTest {

    private final Camunda8Jackson3JsonMapper jsonMapper =
            new Camunda8Jackson3JsonMapper(JsonMapper.builder().build());

    public static class Aggregate {

        public String id;

        public BigDecimal amount;

        public String nullValue;

        public boolean flag;

        public int count;

    }

    public static class AggregateWithDates {

        public OffsetDateTime dueDate;

        public Instant createdAt;

        public LocalDate day;

    }

    private Aggregate aggregate() {

        final var aggregate = new Aggregate();
        aggregate.id = "loan-1";
        aggregate.amount = new BigDecimal("1234.50");
        aggregate.nullValue = null;
        aggregate.flag = false;
        aggregate.count = 0;
        return aggregate;

    }

    /**
     * Same expectations as {@code AggregateWireFormatTest} has for Camunda's fallback mapper: no
     * indentation, {@code null} and zero-valued properties written rather than omitted, {@code BigDecimal}
     * scale preserved. Zeebe variables are read by BPMN expressions and by other workflow modules, so this
     * is the format that must not drift.
     */
    @Test
    void theAggregateWireFormatIsStable() {

        assertThat(jsonMapper.toJson(aggregate())).isEqualTo(
                "{\"id\":\"loan-1\",\"amount\":1234.50,\"nullValue\":null,\"flag\":false,\"count\":0}");

    }

    @Test
    void javaTimeTypesAreSerialisedAsIsoStrings() {

        final var aggregate = new AggregateWithDates();
        aggregate.dueDate = OffsetDateTime.of(2026, 7, 31, 12, 34, 56, 0, ZoneOffset.ofHours(2));
        aggregate.createdAt = Instant.parse("2026-07-31T10:34:56Z");
        aggregate.day = LocalDate.of(2026, 7, 31);

        assertThat(jsonMapper.toJson(aggregate)).isEqualTo(
                "{\"dueDate\":\"2026-07-31T12:34:56+02:00\","
                + "\"createdAt\":\"2026-07-31T10:34:56Z\","
                + "\"day\":\"2026-07-31\"}");

    }

    @Test
    void theAggregateRoundTrips() {

        final var readBack = jsonMapper.fromJson(jsonMapper.toJson(aggregate()), Aggregate.class);

        assertThat(readBack.id).isEqualTo("loan-1");
        assertThat(readBack.amount).isEqualByComparingTo("1234.50");
        assertThat(readBack.nullValue).isNull();

    }

    @Test
    void idTypesSerialiseAsExpected() {

        assertThat(jsonMapper.toJson(UUID.fromString("6b4a1e00-0000-4000-8000-000000000001")))
                .isEqualTo("\"6b4a1e00-0000-4000-8000-000000000001\"");
        assertThat(jsonMapper.toJson("loan-1")).isEqualTo("\"loan-1\"");
        assertThat(jsonMapper.toJson(42L)).isEqualTo("42");

    }

    /**
     * {@code CamundaObjectMapper}'s constructor switches these two features off, which matters for real
     * process variables: Zeebe hands back everything a process carries, not only the fields an aggregate
     * declares.
     */
    @Test
    void unknownPropertiesAreIgnoredAndEmptyBeansDoNotFail() {

        final var readBack = jsonMapper.fromJson(
                "{\"id\":\"loan-1\",\"somethingElse\":\"from another workflow module\"}", Aggregate.class);

        assertThat(readBack.id).isEqualTo("loan-1");
        assertThat(jsonMapper.toJson(new Object())).isEqualTo("{}");

    }

    /**
     * The application's own mapper must not be reconfigured behind its back - Jackson 3 mappers are
     * immutable, so the bridge rebuilds rather than mutates. Jackson 2 based {@code CamundaObjectMapper}
     * does mutate what it is given.
     */
    @Test
    void theApplicationsMapperIsLeftUntouched() {

        final var applicationMapper = JsonMapper.builder().build();

        new Camunda8Jackson3JsonMapper(applicationMapper);

        assertThat(applicationMapper.writeValueAsString(aggregate()))
                .as("and alphabetical ordering, Jackson 3's default, must still apply there as well")
                .isEqualTo(
                        "{\"amount\":1234.50,\"count\":0,\"flag\":false,\"id\":\"loan-1\","
                        + "\"nullValue\":null}");

    }

    /**
     * The three features the bridge sets explicitly, measured on a plain Jackson 3 mapper. Only
     * {@code SORT_PROPERTIES_ALPHABETICALLY} is actually enabled by default - Jackson 3 flipped both fail
     * flags to off, whereas Jackson 2 had them on, which is why {@code CamundaObjectMapper} switched them
     * off explicitly. The two {@code disable(..)} calls in the bridge are therefore no-ops today and are
     * kept only so a future default flip cannot change behaviour silently. If this test goes red, a
     * default moved and the explicit calls started to matter.
     */
    @Test
    void theJacksonDefaultsTheBridgeReliesOnAreMeasured() {

        final var plain = JsonMapper.builder().build();

        assertThat(plain.isEnabled(tools.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY))
                .as("Jackson 3 sorts properties alphabetically, Jackson 2 did not")
                .isTrue();
        assertThat(plain.isEnabled(tools.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS))
                .isFalse();
        assertThat(plain.isEnabled(
                tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))
                .isFalse();

    }

    @Test
    void mapsAreReadBack() {

        assertThat(jsonMapper.fromJsonAsMap("{\"a\":1,\"b\":\"x\"}"))
                .containsExactly(Map.entry("a", 1), Map.entry("b", "x"));
        assertThat(jsonMapper.fromJsonAsStringMap("{\"a\":\"1\"}"))
                .containsExactly(Map.entry("a", "1"));

    }

    @Test
    void jsonIsValidatedAndNormalised() {

        assertThat(jsonMapper.validateJson("myProperty", "{ \"a\" : 1 }")).isEqualTo("{\"a\":1}");
        assertThat(jsonMapper.validateJson("myProperty",
                new ByteArrayInputStream("{ \"a\" : 1 }".getBytes(StandardCharsets.UTF_8))))
                .isEqualTo("{\"a\":1}");

    }

    /**
     * Callers catch {@code InternalClientException} - Camunda's contract. Jackson 3 throws the unchecked
     * {@code JacksonException} where Jackson 2 threw {@code IOException}, so the {@code catch} clause in
     * the bridge had to change and this test makes sure the wrapping still happens.
     */
    @Test
    void failuresAreWrappedInInternalClientException() {

        assertThatThrownBy(() -> jsonMapper.fromJson("not json", Aggregate.class))
                .isInstanceOf(InternalClientException.class)
                .hasMessageContaining("Failed to deserialize json 'not json' to class");

        assertThatThrownBy(() -> jsonMapper.validateJson("myProperty", "not json"))
                .isInstanceOf(InternalClientException.class)
                .hasMessage("Failed to validate json input 'not json' for property 'myProperty'");

        assertThatThrownBy(() -> jsonMapper.validateJson("myProperty",
                new ByteArrayInputStream("not json".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(InternalClientException.class)
                .hasMessage("Failed to validate json input stream for property 'myProperty'");

        assertThatThrownBy(() -> jsonMapper.fromJsonAsMap("not json"))
                .isInstanceOf(InternalClientException.class)
                .hasMessageContaining("to 'Map<String, Object>'");

    }

}
