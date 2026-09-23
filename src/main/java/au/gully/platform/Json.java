package au.gully.platform;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;


/**
 * JSON in and out of {@code jsonb} columns and the API's own byte bodies, over Boot's Jackson 3 mapper.
 */
@Component
@RequiredArgsConstructor
public class Json {

    private final ObjectMapper mapper;

    public String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    public <T> T read(String json, Class<T> type) {
        return mapper.readValue(json, type);
    }
}
