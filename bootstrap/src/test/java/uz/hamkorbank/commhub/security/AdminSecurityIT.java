package uz.hamkorbank.commhub.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.assertj.core.api.ObjectAssert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import uz.hamkorbank.commhub.adapter.in.admin.AdminApi;
import uz.hamkorbank.commhub.adapter.in.rest.ApiV1;
import uz.hamkorbank.commhub.bootstrap.NotificationHubApplication;
import uz.hamkorbank.commhub.support.HubTestContainers;
import uz.hamkorbank.commhub.support.KeycloakTokens;

/**
 * The admin panel is unreachable without a token, and reachable only with the right role (ADR-0037).
 *
 * <p>Against a real Keycloak with the realm {@code docker compose} imports, so what is checked is the
 * whole chain a browser goes through: a group in the realm export becomes a claim in the token, the
 * claim becomes a {@code ROLE_} authority in the converter, and the authority is what
 * {@code AdminAuthority} matches. A hand-signed token would only prove the converter agrees with this
 * test.
 *
 * <p>401 and 403 are asserted apart on purpose. They are the difference between "who are you" and "may
 * you", and a chain that answered 403 to an anonymous caller would look protected while telling every
 * scanner that the endpoint exists and is merely out of reach.
 */
@Tag("integration")
@SpringBootTest(classes = NotificationHubApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
        properties = {
            "commhub.outbox.relay.poll-interval-ms=3600000",
            "commhub.config.cache.refresh-interval=30s",
            "commhub.provider.health.initial-delay=1h",
            "commhub.metrics.backlog-refresh-interval=1h"
        })
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AdminSecurityIT {

    private final JdbcClient jdbc;
    private final RestClient rest;

    AdminSecurityIT(JdbcClient jdbc, @Value("${local.server.port}") int port) {
        this.jdbc = jdbc;
        this.rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> {})
                .build();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HubTestContainers.register(registry);
    }

    @Test
    @DisplayName("no token is 401, and a token the issuer never minted is 401 too")
    void refusesCallersWithoutAValidToken() {
        // Act + Assert
        status(AdminApi.DASHBOARD, null).isEqualTo(HttpStatus.UNAUTHORIZED);
        status(AdminApi.DASHBOARD, "not-a-token").isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("the demo user is an administrator: the configuration screens answer")
    void admitsTheDemoAdministrator() {
        // Act + Assert
        status(AdminApi.PROVIDERS, KeycloakTokens.admin()).isEqualTo(HttpStatus.OK);
        status(AdminApi.STREAMS, KeycloakTokens.admin()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a viewer is authenticated and still refused the configuration: 403, not 401")
    void refusesAViewerTheConfiguration() {
        // Act + Assert
        status(AdminApi.PROVIDERS, KeycloakTokens.of("viewer")).isEqualTo(HttpStatus.FORBIDDEN);
        status(AdminApi.MESSAGES, KeycloakTokens.of("viewer")).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("SEC-08: the journal is the auditor's and the administrator's, and nobody else's")
    void keepsTheAuditJournalToItsRoles() {
        // Act + Assert
        status(AdminApi.AUDIT, KeycloakTokens.of("auditor")).isEqualTo(HttpStatus.OK);
        status(AdminApi.AUDIT, KeycloakTokens.admin()).isEqualTo(HttpStatus.OK);
        status(AdminApi.AUDIT, KeycloakTokens.of("analyst")).isEqualTo(HttpStatus.FORBIDDEN);
        status(AdminApi.STATISTICS, KeycloakTokens.of("analyst")).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("SEC-08: the journal names the login of the employee, not the UUID of their SSO account")
    void journalsTheOperatorByLogin() {
        // Arrange: a message search is what SEC-08 audits, and it is the operator's own screen
        status(AdminApi.MESSAGES, KeycloakTokens.admin()).isEqualTo(HttpStatus.OK);

        // Act
        Long byLogin = jdbc.sql("select count(*) from comm_hub.audit_log where username = :username")
                .param("username", KeycloakTokens.ADMIN_USER)
                .query(Long.class)
                .single();

        // Assert
        assertThat(byLogin).isPositive();
    }

    @Test
    @DisplayName("the source-system API keeps its own gating: this contour has not switched SEC-01 on")
    void leavesTheSourceSystemApiAsTheContourConfiguredIt() {
        // Act + Assert: not 401 — the call is answered on its merits, not refused for its caller
        status(ApiV1.MESSAGES + "/" + UUID.randomUUID(), null).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("NF-05: the probes stay anonymous, the rest of the actuator does not")
    void keepsTheProbesOpenAndTheRestClosed() {
        // Act + Assert
        status("/actuator/health", null).isEqualTo(HttpStatus.OK);
        status("/actuator/env", null).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * The status of a GET, described by the body it came with.
     *
     * <p>An assertion that says only "expected 403 but was 500" sends whoever reads it to a log they no
     * longer have; the {@code problem+json} the Hub answered names the reason on the spot.
     */
    private ObjectAssert<HttpStatusCode> status(String path, String token) {
        ResponseEntity<String> response = rest.get()
                .uri(path)
                .headers(headers -> {
                    if (token != null) {
                        headers.setBearerAuth(token);
                    }
                })
                .retrieve()
                .toEntity(String.class);
        return assertThat(response.getStatusCode()).as("GET %s answered %s", path, response.getBody());
    }
}
