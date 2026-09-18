package au.gully.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.TimeUnit;

/**
 * {@code GET /api/v1/contract/reading.schema.json}: the reading's shape, as the JSON Schema both
 * repositories keep a copy of (docs/06 item 9). Public, so a consumer can compare its copy at start
 * and say so if the two have drifted.
 */
@RestController
@Tag(name = "contract", description = "The reading's shape, written down")
public class ContractController {

    public static final String PATH = "contract/reading.schema.json";

    @GetMapping(value = "/api/v1/contract/reading.schema.json", produces = "application/schema+json")
    @Operation(summary = "The JSON Schema every reading matches")
    public ResponseEntity<byte[]> reading() {
        try {
            byte[] bytes = new ClassPathResource(PATH).getContentAsByteArray();
            return ResponseEntity.ok().cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
                    .contentType(MediaType.parseMediaType("application/schema+json")).body(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
