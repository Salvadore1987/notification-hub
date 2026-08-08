package uz.hamkorbank.commhub.adapter.in.rest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the OpenAPI 3.1 contract of the source-system API (IR-03).
 *
 * <p>The document is a resource of this module, not a runtime introspection of the controllers.
 * springdoc, which would generate it, has no release for Spring Boot 4 yet — its current branch is
 * built against Spring 6 and Jackson 2, neither of which is on this classpath. Rather than pin the
 * whole web stack to an older generation for the sake of a generator, the contract is written next to
 * the code and {@code OpenApiContractTest} fails the build when the two disagree: every mapping the
 * controllers declare has to appear in the document, and every operation in the document has to exist
 * in the code.
 *
 * <p>Left in place when springdoc catches up: the endpoint stays, its body starts being generated.
 */
@RestController
public class OpenApiController {

    /** Where the contract is published; the same path the delivery artefact of IR-03 points at. */
    public static final String PATH = ApiV1.BASE + "/openapi.yaml";

    /** Resource holding the contract, shared with the test that keeps it in step with the code. */
    public static final String RESOURCE = "openapi/comm-hub-api-v1.yaml";

    private static final MediaType YAML = MediaType.parseMediaType("application/yaml");

    @GetMapping(path = PATH, produces = "application/yaml")
    public ResponseEntity<String> contract() {
        return ResponseEntity.ok().contentType(YAML).body(read());
    }

    /** Reads the contract from the classpath; used by the drift test as well. */
    public static String read() {
        Resource resource = new ClassPathResource(RESOURCE);
        try (InputStream stream = resource.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("The OpenAPI contract %s is missing from the build".formatted(RESOURCE), e);
        }
    }
}
