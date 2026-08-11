package uz.hamkorbank.commhub.adapter.in.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import uz.hamkorbank.commhub.application.port.in.command.ProviderStateCommand;
import uz.hamkorbank.commhub.domain.model.type.BalancingStrategy;
import uz.hamkorbank.commhub.domain.model.type.BatchStatus;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ChannelStatus;
import uz.hamkorbank.commhub.domain.model.type.ConnectionStatus;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;
import uz.hamkorbank.commhub.domain.model.type.IntegrationType;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.Priority;
import uz.hamkorbank.commhub.domain.model.type.ProviderHealthStatus;
import uz.hamkorbank.commhub.domain.model.type.PushPlatform;
import uz.hamkorbank.commhub.domain.model.type.QuietHoursBehavior;
import uz.hamkorbank.commhub.domain.model.type.QuotaExhaustionBehavior;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.type.SmsEncoding;
import uz.hamkorbank.commhub.domain.model.type.StreamStatus;
import uz.hamkorbank.commhub.domain.model.type.SuppressionReason;
import uz.hamkorbank.commhub.domain.model.type.TemplateCatalogStatus;
import uz.hamkorbank.commhub.domain.model.type.TemplateStatus;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;

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
            AdministrationController.class,
            SendAdminController.class);

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

    /**
     * Schemas of the contract that are a domain enum spelled out, and the enum each one mirrors.
     *
     * <p>These travel as {@code …name()} through {@code AdminViewMapper} and come back through
     * {@code AdminValues.requiredEnum}, so a name the document invents is an endpoint the panel
     * cannot call, and a name the document omits is a value the panel cannot render.
     */
    private static final Map<String, Class<? extends Enum<?>>> ENUM_SCHEMAS = Map.ofEntries(
            Map.entry("Channel", Channel.class),
            Map.entry("BalancingStrategy", BalancingStrategy.class),
            Map.entry("ContentLocale", ContentLocale.class),
            Map.entry("MessageStatus", MessageStatus.class),
            Map.entry("BatchStatus", BatchStatus.class),
            Map.entry("RejectionReason", RejectionReason.class),
            Map.entry("SuppressionReason", SuppressionReason.class),
            Map.entry("TemplateVersionStatus", TemplateStatus.class),
            Map.entry("TemplateCatalogStatus", TemplateCatalogStatus.class),
            Map.entry("TrafficClass", TrafficClass.class),
            Map.entry("Priority", Priority.class),
            Map.entry("IntegrationType", IntegrationType.class),
            Map.entry("StreamStatus", StreamStatus.class),
            Map.entry("ConnectionStatus", ConnectionStatus.class),
            Map.entry("ChannelStatus", ChannelStatus.class),
            Map.entry("ProviderHealthStatus", ProviderHealthStatus.class),
            Map.entry("ProviderState", ProviderStateCommand.ProviderState.class),
            Map.entry("QuotaExhaustionBehavior", QuotaExhaustionBehavior.class),
            Map.entry("QuietHoursBehavior", QuietHoursBehavior.class),
            Map.entry("PushPlatform", PushPlatform.class),
            Map.entry("SmsEncoding", SmsEncoding.class));

    @Test
    @DisplayName("UI-02: every enum of the contract spells the domain vocabulary, name for name")
    void enumsMatchTheDomain() {
        // Arrange
        String contract = AdminOpenApiController.read();

        // Act + Assert — the document is hand-written, and a status it invents is one the panel sends
        // and the BFF answers 400 to; the SPA generates its types from here and cannot notice
        ENUM_SCHEMAS.forEach((schema, type) -> assertThat(declaredEnum(contract, schema))
                .as("schema %s must list exactly the constants of %s", schema, type.getSimpleName())
                .containsExactlyInAnyOrderElementsOf(constantsOf(type)));
    }

    /**
     * Path parameters that carry an identifier an operator types, and the value object that judges it.
     *
     * <p>The three disagree about case on purpose — a stream id is lowercase, a provider and template
     * code uppercase — which is precisely why the rule has to be written down where the panel can put
     * it in front of whoever is typing, instead of arriving as a 400 with a regular expression in it.
     */
    private static final Map<String, Class<?>> IDENTIFIER_PARAMETERS = Map.of(
            "StreamIdPath", StreamId.class,
            "ProviderCodePath", ProviderCode.class,
            "ProviderProfileCodePath", ProviderCode.class,
            "TemplateCodePath", TemplateCode.class);

    @Test
    @DisplayName("UI-02: every identifier parameter publishes the pattern its value object enforces")
    void identifierPatternsMatchTheDomain() {
        // Arrange
        String contract = AdminOpenApiController.read();

        // Act + Assert
        IDENTIFIER_PARAMETERS.forEach((parameter, type) -> assertThat(declaredPattern(contract, parameter))
                .as("parameter %s must publish the pattern of %s", parameter, type.getSimpleName())
                .isEqualTo(patternOf(type)));
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
                        "name: send",
                        "name: administration");
    }

    private static List<String> constantsOf(Class<? extends Enum<?>> type) {
        return Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
    }

    /**
     * The {@code enum:} flow sequence of a named schema, which may be wrapped over several lines.
     *
     * <p>Read as text rather than through a YAML parser: this module has none on its test classpath,
     * and the shape being read is one line of one document that is written by hand anyway.
     */
    private static Set<String> declaredEnum(String contract, String schema) {
        List<String> lines = contract.lines().toList();
        int declaration = lines.indexOf("    " + schema + ":");
        assertThat(declaration)
                .as("the contract must declare schema %s", schema)
                .isNotNegative();
        StringBuilder flow = new StringBuilder();
        for (int i = declaration + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (flow.isEmpty() && isSchemaDeclaration(line)) {
                break; // the next schema started, so this one carries no enum
            }
            if (flow.isEmpty() && !line.contains("enum: [")) {
                continue;
            }
            flow.append(line);
            if (line.contains("]")) {
                break;
            }
        }
        assertThat(flow.toString()).as("schema %s must be an enum", schema).contains("enum: [");
        String body = flow.substring(flow.indexOf("[") + 1, flow.lastIndexOf("]"));
        return Arrays.stream(body.split(",")).map(String::trim).collect(Collectors.toSet());
    }

    /** The regular expression a value object enforces, read off the constant it keeps it in. */
    private static String patternOf(Class<?> type) {
        try {
            Field pattern = type.getDeclaredField("PATTERN");
            pattern.setAccessible(true);
            return ((Pattern) pattern.get(null)).pattern();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(type.getSimpleName() + " must keep its format in PATTERN", e);
        }
    }

    /** The {@code pattern:} a named path parameter publishes, quoted in its inline schema. */
    private static String declaredPattern(String contract, String parameter) {
        List<String> lines = contract.lines().toList();
        int declaration = lines.indexOf("    " + parameter + ":");
        assertThat(declaration)
                .as("the contract must declare parameter %s", parameter)
                .isNotNegative();
        for (int i = declaration + 1; i < lines.size() && !isSchemaDeclaration(lines.get(i)); i++) {
            if (lines.get(i).contains("pattern:")) {
                String tail = lines.get(i).substring(lines.get(i).indexOf("pattern:"));
                return tail.substring(tail.indexOf('\'') + 1, tail.lastIndexOf('\''));
            }
        }
        throw new AssertionError("parameter " + parameter + " publishes no pattern");
    }

    /** A schema key of {@code components/schemas}: four spaces of indent and nothing after the colon. */
    private static boolean isSchemaDeclaration(String line) {
        return line.startsWith("    ") && !line.startsWith("     ") && line.endsWith(":");
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
