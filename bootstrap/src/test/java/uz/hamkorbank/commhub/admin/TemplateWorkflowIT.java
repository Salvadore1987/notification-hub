package uz.hamkorbank.commhub.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import uz.hamkorbank.commhub.adapter.in.admin.AdminApi;
import uz.hamkorbank.commhub.bootstrap.NotificationHubApplication;
import uz.hamkorbank.commhub.support.HubTestContainers;
import uz.hamkorbank.commhub.support.KeycloakTokens;

/**
 * The template workflow as the panel drives it, over HTTP (FR-4.1, FR-4.2, §11.2).
 *
 * <p>Written after the panel spent a release unable to publish anything: the contract and the SPA
 * spoke of {@code IN_REVIEW} and {@code REJECTED}, the domain and the {@code CHECK} constraint of
 * {@code template_version} of {@code ON_REVIEW} and nothing else, and the "to review" button
 * therefore answered 400 forever. Every layer had tests and none of them crossed the boundary where
 * the two vocabularies met — so this one walks the whole path with real tokens, exactly as an
 * operator does.
 *
 * <p>Two users, because the rule under test needs two: the realm gives {@code template-manager} the
 * role and {@code demo} the ADMIN that includes it, and the maker/checker of FR-4.2 is the reason
 * one cannot finish the journey alone.
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
class TemplateWorkflowIT {

    private static final String CODE = "IT_WORKFLOW_OTP";
    private static final String AUTHOR = "template-manager";

    private final RestClient rest;

    TemplateWorkflowIT(@Value("${local.server.port}") int port) {
        this.rest = RestClient.builder()
                .baseUrl("http://localhost:" + port + AdminApi.TEMPLATES)
                .defaultStatusHandler(status -> true, (request, response) -> {})
                .build();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HubTestContainers.register(registry);
    }

    @Test
    @DisplayName("FR-4.1, FR-4.2: a draft reaches PUBLISHED through review and a second person")
    void draftReachesPublished() {
        // Arrange — the author registers the card and writes the first version
        assertThat(post("/" + CODE, Map.of("channel", "SMS", "direction", "сервисные", "owner", "retail"), AUTHOR)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<String> draft =
                put("/" + CODE + "/versions", Map.of("locale", "RU", "text", "Ваш код: {CODE}"), AUTHOR);
        assertThat(draft.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(draft.getBody()).contains("\"status\":\"DRAFT\"");

        // Act + Assert — the state the panel sends is the state the domain knows
        ResponseEntity<String> review = state("ON_REVIEW", AUTHOR);
        assertThat(review.getStatusCode())
                .as("submitting for review answered %s", review.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(review.getBody()).contains("\"status\":\"ON_REVIEW\"");

        // Act + Assert — the author may not publish their own version (FR-4.2)
        ResponseEntity<String> byAuthor = state("PUBLISHED", AUTHOR);
        assertThat(byAuthor.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(byAuthor.getBody()).contains("maker/checker");

        // Act + Assert — a second person does, and only then is the template sendable
        ResponseEntity<String> published = state("PUBLISHED", KeycloakTokens.ADMIN_USER);
        assertThat(published.getStatusCode())
                .as("publishing answered %s", published.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(published.getBody()).contains("\"status\":\"PUBLISHED\"");
    }

    @Test
    @DisplayName("§11.2: the card is ACTIVE from the start — it is the version that carries DRAFT")
    void theCardIsActiveWhileItsVersionIsADraft() {
        // Arrange
        String code = CODE + "_CARD";
        post("/" + code, Map.of("channel", "SMS", "direction", "сервисные", "owner", "retail"), AUTHOR);
        put("/" + code + "/versions", Map.of("locale", "RU", "text", "Ваш код: {CODE}"), AUTHOR);

        // Act
        ResponseEntity<String> card = get("/" + code, AUTHOR);

        // Assert — the two vocabularies of one screen: the card is in the catalogue, the version is not
        // sendable yet, and nothing has to be done to the card to make it so
        assertThat(card.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(card.getBody()).contains("\"catalogStatus\":\"ACTIVE\"", "\"status\":\"DRAFT\"");
    }

    @Test
    @DisplayName("FR-4.1: a status the panel invents is refused with the vocabulary that exists")
    void refusesAStatusOutsideTheVocabulary() {
        // Arrange + Act — the spelling the panel used to send
        ResponseEntity<String> answer = state("IN_REVIEW", AUTHOR);

        // Assert
        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(answer.getBody()).contains("DRAFT", "ON_REVIEW", "PUBLISHED", "ARCHIVED");
    }

    private ResponseEntity<String> state(String status, String user) {
        return post("/" + CODE + "/versions/RU/1/state/" + status, null, user);
    }

    private ResponseEntity<String> get(String path, String user) {
        return rest.get()
                .uri(path)
                .headers(headers -> headers.setBearerAuth(KeycloakTokens.of(user)))
                .retrieve()
                .toEntity(String.class);
    }

    private ResponseEntity<String> post(String path, Object body, String user) {
        RestClient.RequestBodySpec request =
                rest.post().uri(path).headers(headers -> headers.setBearerAuth(KeycloakTokens.of(user)));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.retrieve().toEntity(String.class);
    }

    private ResponseEntity<String> put(String path, Object body, String user) {
        return rest.put()
                .uri(path)
                .headers(headers -> headers.setBearerAuth(KeycloakTokens.of(user)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class);
    }
}
