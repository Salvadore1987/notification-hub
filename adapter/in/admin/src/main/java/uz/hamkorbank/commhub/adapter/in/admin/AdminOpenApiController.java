package uz.hamkorbank.commhub.adapter.in.admin;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminAuthority;

/**
 * Serves the OpenAPI 3.1 contract of the admin BFF (UI-02).
 *
 * <p>Written by hand next to the code for the same reason as the source-system contract — springdoc has
 * no Spring Boot 4 release — and kept honest the same way: {@code AdminOpenApiContractTest} walks the
 * mappings the controllers declare and fails the build when one of them is missing from the document.
 *
 * <p>This one earns its keep more than the other. The SPA of Phase 16 generates its API client and its
 * types from this file, so an endpoint that is not in it is an endpoint the frontend cannot call, and a
 * shape that drifts is a compile error over there rather than a runtime surprise.
 *
 * <p>Deliberately not open to the world: it is a map of every administrative endpoint of the Hub, which
 * is worth rather more to somebody who should not have it than the source-system contract is. It is
 * excluded from the drift test's own inventory for the obvious reason that it documents the others.
 */
@RestController
public class AdminOpenApiController {

    /** Where the contract is published. */
    public static final String PATH = AdminApi.BASE + "/openapi.yaml";

    /** Resource holding the contract, shared with the test that keeps it in step with the code. */
    public static final String RESOURCE = "openapi/comm-hub-admin-v1.yaml";

    private static final MediaType YAML = MediaType.parseMediaType("application/yaml");

    @GetMapping(path = PATH, produces = "application/yaml")
    @PreAuthorize(AdminAuthority.ANY)
    public ResponseEntity<String> contract() {
        return ResponseEntity.ok().contentType(YAML).body(read());
    }

    /** Reads the contract from the classpath; used by the drift test as well. */
    public static String read() {
        Resource resource = new ClassPathResource(RESOURCE);
        try (InputStream stream = resource.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "The admin OpenAPI contract %s is missing from the build".formatted(RESOURCE), e);
        }
    }
}
