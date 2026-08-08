package uz.hamkorbank.commhub.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * Keeps the published OpenAPI 3.1 contract in step with the code (IR-03).
 *
 * <p>The document is written by hand — springdoc has no Spring Boot 4 release yet — so something has
 * to stop it from quietly drifting. This walks the mappings the controllers actually declare and
 * requires an operation for each; adding an endpoint without documenting it fails the build, which is
 * the property a generator would have given us.
 */
class OpenApiContractTest {

    private static final List<Class<?>> CONTROLLERS = List.of(MessageController.class, BatchController.class);

    @Test
    @DisplayName("IR-03: every endpoint of the source-system API is in the published contract")
    void everyMappingIsDocumented() {
        // Arrange
        String contract = OpenApiController.read();

        // Act
        Set<String> operations = mappings();

        // Assert
        assertThat(operations).isNotEmpty();
        for (String operation : operations) {
            String path = operation.substring(operation.indexOf(' ') + 1);
            String method = operation.substring(0, operation.indexOf(' ')).toLowerCase(Locale.ROOT);
            assertThat(contract)
                    .as("OpenAPI contract must document %s", operation)
                    .contains(pathKey(path));
            assertThat(contract)
                    .as("OpenAPI contract must document the %s of %s", method, path)
                    .contains(method + ":");
        }
    }

    @Test
    @DisplayName("IR-03: the contract declares OpenAPI 3.1 and the base path of §8.2")
    void declaresTheRightVersionAndBase() {
        // Arrange + Act
        String contract = OpenApiController.read();

        // Assert
        assertThat(contract).startsWith("openapi: 3.1.");
        assertThat(contract).contains("/api/v1");
    }

    @Test
    @DisplayName("IR-01: the contract lists the machine-readable error codes the adapter can return")
    void listsTheErrorCodes() {
        // Arrange
        String contract = OpenApiController.read();

        // Act + Assert
        assertThat(contract)
                .contains(
                        "VALIDATION_FAILED",
                        "DUPLICATE",
                        "STREAM_SUSPENDED",
                        "QUOTA_EXCEEDED",
                        "TEMPLATE_NOT_PUBLISHED",
                        "RATE_LIMITED");
    }

    /** Paths in the document are relative to the {@code /api/v1} server URL, as §8.2 publishes them. */
    private static String pathKey(String path) {
        return path.substring(ApiV1.BASE.length()) + ":";
    }

    private static Set<String> mappings() {
        Set<String> operations = new LinkedHashSet<>();
        for (Class<?> controller : CONTROLLERS) {
            String base = base(controller);
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                for (RequestMethod verb : mapping.method()) {
                    for (String path : paths(mapping)) {
                        operations.add(verb.name() + " " + base + path);
                    }
                }
            }
        }
        return operations;
    }

    private static List<String> paths(RequestMapping mapping) {
        List<String> paths = new ArrayList<>(List.of(mapping.path()));
        if (paths.isEmpty()) {
            paths.add("");
        }
        return paths;
    }

    private static String base(Class<?> controller) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
        return mapping == null || mapping.path().length == 0 ? "" : mapping.path()[0];
    }
}
