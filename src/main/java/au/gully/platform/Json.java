package au.gully.platform;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * JSON in and out of {@code jsonb} columns and the API's own byte bodies, over Boot's Jackson 3 mapper.
 */
@Component
@RequiredArgsConstructor
public class Json {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final ObjectMapper mapper;

    public String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    public <T> T read(String json, Class<T> type) {
        return mapper.readValue(json, type);
    }

    public Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return mapper.readValue(json, MAP);
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
