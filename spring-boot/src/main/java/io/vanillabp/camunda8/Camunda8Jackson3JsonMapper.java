package io.vanillabp.camunda8;

import io.camunda.client.api.JsonMapper;
import io.camunda.client.api.command.InternalClientException;
import java.io.InputStream;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;

/**
 * Camunda's {@link JsonMapper} on top of the application's Jackson 3 mapper.
 * <p>
 * Why this class exists: {@code camunda-spring-boot-4-starter:8.8.33} ships exactly one
 * {@code JsonMapperConfiguration}, and it injects a <b>Jackson 2</b>
 * {@code com.fasterxml.jackson.databind.ObjectMapper} with {@code @Autowired(required = false)}. On Spring
 * Boot 4 there is no such bean - Boot auto-configures Jackson 3 - so the parameter stays {@code null} and
 * Camunda falls back to {@code new CamundaObjectMapper()}, which wraps a bare Jackson 2 mapper with
 * <b>no modules registered</b>. Consequence: workflow aggregates carrying {@code OffsetDateTime},
 * {@code Instant} or {@code LocalDate} cannot be sent to Zeebe at all. See
 * {@code io.vanillabp.camunda8.wiring.JsonMapperSelectionDiagnosticTest}, which pins that behaviour down.
 * <p>
 * This bridge does what Camunda itself does from 8.9 on: it uses the mapper the application already
 * configured, so the JSON of process variables follows the application's Jackson settings and the Java 8
 * date and time types work, because Jackson 3 has them built in.
 * <p>
 * The two feature changes below mirror {@code CamundaObjectMapper}'s constructor, which mutates the mapper
 * it is handed. A Jackson 3 mapper is immutable, so this creates a reconfigured copy instead - the
 * application's own mapper stays untouched, which is the better behaviour anyway: nobody expects a
 * workflow adapter to change how the rest of the application serializes JSON.
 * <p>
 * Property order is pinned to declaration order. Jackson 3 sorts alphabetically by default where Jackson 2
 * did not, and Zeebe variables are a wire format shared between workflow modules and process instances of
 * different vintages. Keeping the order means the JSON of an aggregate without date fields is
 * byte-identical to what Camunda's fallback mapper produced - the only intended change of this bridge is
 * that date-bearing aggregates work at all. An application that deliberately enables alphabetical sorting
 * for its own JSON therefore does not get it for Zeebe variables; that is the trade-off, and the wire
 * format wins.
 * <p>
 * Error messages and the {@code InternalClientException} wrapping are kept identical to
 * {@code CamundaObjectMapper}, so anything matching on them keeps working. Jackson 3 throws the unchecked
 * {@link JacksonException} where Jackson 2 threw {@code IOException}.
 */
public class Camunda8Jackson3JsonMapper implements JsonMapper {

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE =
            new TypeReference<>() { };

    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE_REFERENCE =
            new TypeReference<>() { };

    private final tools.jackson.databind.json.JsonMapper jsonMapper;

    public Camunda8Jackson3JsonMapper(
            final tools.jackson.databind.json.JsonMapper jsonMapper) {

        this.jsonMapper = jsonMapper
                .rebuild()
                .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();

    }

    @Override
    public <T> T fromJson(
            final String json,
            final Class<T> typeClass) {

        try {
            return jsonMapper.readValue(json, typeClass);
        } catch (JacksonException e) {
            throw new InternalClientException(
                    String.format("Failed to deserialize json '%s' to class '%s'", json, typeClass), e);
        }

    }

    @Override
    public <T> T transform(
            final Object value,
            final Class<T> typeClass) {

        try {
            return jsonMapper.convertValue(value, typeClass);
        } catch (JacksonException e) {
            throw new InternalClientException(
                    String.format("Failed to transform object '%s' to class '%s'", value, typeClass), e);
        }

    }

    @Override
    public Map<String, Object> fromJsonAsMap(
            final String json) {

        try {
            return jsonMapper.readValue(json, MAP_TYPE_REFERENCE);
        } catch (JacksonException e) {
            throw new InternalClientException(
                    String.format("Failed to deserialize json '%s' to 'Map<String, Object>'", json), e);
        }

    }

    @Override
    public Map<String, String> fromJsonAsStringMap(
            final String json) {

        try {
            return jsonMapper.readValue(json, STRING_MAP_TYPE_REFERENCE);
        } catch (JacksonException e) {
            throw new InternalClientException(
                    String.format("Failed to deserialize json '%s' to 'Map<String, String>'", json), e);
        }

    }

    @Override
    public String toJson(
            final Object value) {

        try {
            return jsonMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new InternalClientException(
                    String.format("Failed to serialize object '%s' to json", value), e);
        }

    }

    @Override
    public String validateJson(
            final String propertyName,
            final String jsonInput) {

        try {
            return jsonMapper.readTree(jsonInput).toString();
        } catch (JacksonException e) {
            throw new InternalClientException(
                    String.format("Failed to validate json input '%s' for property '%s'",
                            jsonInput, propertyName), e);
        }

    }

    @Override
    public String validateJson(
            final String propertyName,
            final InputStream jsonInput) {

        try {
            return jsonMapper.readTree(jsonInput).toString();
        } catch (JacksonException e) {
            throw new InternalClientException(
                    String.format("Failed to validate json input stream for property '%s'",
                            propertyName), e);
        }

    }

}
