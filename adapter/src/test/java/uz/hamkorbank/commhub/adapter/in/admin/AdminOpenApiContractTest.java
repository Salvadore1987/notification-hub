package uz.hamkorbank.commhub.adapter.in.admin;

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
 * Keeps the published admin contract in step with the code (§11.2, UI-02).
 *
 * <p>The document is written by hand — springdoc has no Spring Boot 4 release — so something has to
 * stop it from quietly drifting. This walks the mappings the controllers actually declare and requires
 * an operation for each; adding an endpoint without documenting it fails the build.
 *
 * <p>It matters more here than for the source-system contract, because the SPA of Phase 16 generates
 * its client and its types from this file: an endpoint missing from it is an endpoint the frontend
 * cannot call.
 */
class AdminOpenApiContractTest {

    /**
     * Every controller of the section, listed rather than scanned.
     *
     * <p>A classpath scan would keep this list current by itself and would also quietly stop finding
     * anything the day the package moves. A list fails to compile instead.
     */
    private static final List<Class<?>> CONTROLLERS = List.of(
            DashboardController.class,
            BatchAdminController.class,
            MessageAdminController.class,
            DlqAdminController.class,
            StreamAdminController.class,
            ChannelAdminController.class,
            ProviderAdminController.class,
            RoutingAdminController.class,
            TemplateAdminController.class,
            SuppressionAdminController.class,
            StatisticsController.class,
            AuditController.class,
            AdministrationController.class);

    @Test
    @DisplayName("§11.2: every endpoint of the admin BFF is in the published contract")
    void everyMappingIsDocumented() {
        // Arrange
        String contract = AdminOpenApiController.read();

        // Act
        Set<String> operations = mappings();

        // Assert
        assertThat(operations).hasSizeGreaterThan(40);
        for (String operation : operations) {
            String path = operation.substring(operation.indexOf(' ') + 1);
            String method = operation.substring(0, operation.indexOf(' ')).toLowerCase(Locale.ROOT);
            assertThat(contract)
                    .as("admin OpenAPI contract must document %s", operation)
                    .contains(pathKey(path));
            assertThat(contract)
                    .as("admin OpenAPI contract must document the %s of %s", method, path)
                    .contains(method + ":");
        }
    }

    @Test
    @DisplayName("UI-02: the contract declares OpenAPI 3.1, the admin base path and OIDC")
    void declaresTheRightVersionBaseAndSecurity() {
        // Arrange + Act
        String contract = AdminOpenApiController.read();

        // Assert
        assertThat(contract).startsWith("openapi: 3.1.");
        assertThat(contract).contains(AdminApi.BASE);
        assertThat(contract).contains("openIdConnect");
    }

    @Test
    @DisplayName("§11.2: every section of the admin panel has a tag in the contract")
    void coversEverySection() {
        // Arrange + Act
        String contract = AdminOpenApiController.read();

        // Assert
        assertThat(contract)
                .contains(
                        "name: dashboard",
                        "name: batches",
                        "name: messages",
                        "name: dlq",
                        "name: streams",
                        "name: channels",
                        "name: providers",
                        "name: routing",
                        "name: templates",
                        "name: suppressions",
                        "name: statistics",
                        "name: audit",
                        "name: administration");
    }

    /** Paths in the document are relative to the {@code /api/admin/v1} server URL. */
    private static String pathKey(String path) {
        return path.substring(AdminApi.BASE.length()) + ":";
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
