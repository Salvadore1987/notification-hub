package uz.hamkorbank.commhub.adapter.out.provider.apns;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import uz.hamkorbank.commhub.domain.model.type.ErrorClass;

/** The APNs reason table of PU-08, and what each answer does to the token and the breaker. */
class ApnsResponseCatalogTest {

    @ParameterizedTest(name = "{0} (HTTP {1}) is {2}")
    @DisplayName("PU-08: every documented reason lands in the class the specification gives it")
    @CsvSource({
        "Unregistered,410,NON_RETRYABLE",
        "BadDeviceToken,400,NON_RETRYABLE",
        "DeviceTokenNotForTopic,400,NON_RETRYABLE",
        "PayloadTooLarge,413,NON_RETRYABLE",
        "BadRequest,400,NON_RETRYABLE",
        "TooManyRequests,429,RETRYABLE",
        "InternalServerError,500,RETRYABLE",
        "ServiceUnavailable,503,RETRYABLE",
        "ExpiredProviderToken,403,BLOCKING",
        "InvalidProviderToken,403,BLOCKING",
    })
    void classifiesTheDocumentedReasons(String reason, int status, ErrorClass expected) {
        assertThat(ApnsResponseCatalog.classify(reason, status)).isEqualTo(expected);
    }

    @Test
    @DisplayName("PU-08: 410 retires the token; a payload Apple refused does not")
    void retiresOnlyDeadTokens() {
        assertThat(ApnsResponseCatalog.invalidatesToken(ApnsResponseCatalog.UNREGISTERED, 410))
                .isTrue();
        assertThat(ApnsResponseCatalog.invalidatesToken(ApnsResponseCatalog.BAD_DEVICE_TOKEN, 400))
                .isTrue();
        assertThat(ApnsResponseCatalog.invalidatesToken(ApnsResponseCatalog.PAYLOAD_TOO_LARGE, 413))
                .isFalse();
    }

    @Test
    @DisplayName("§9.4.2: a token registered for another application is as dead as an uninstalled one")
    void retiresATokenOfAnotherTopic() {
        assertThat(ApnsResponseCatalog.invalidatesToken(ApnsResponseCatalog.DEVICE_TOKEN_NOT_FOR_TOPIC, 400))
                .isTrue();
    }

    @Test
    @DisplayName("PR-01: dead devices never count against the health of APNs, expired credentials do")
    void countsOnlyWhatDescribesApns() {
        assertThat(ApnsResponseCatalog.countsAsProviderFailure(ApnsResponseCatalog.UNREGISTERED, 410))
                .isFalse();
        assertThat(ApnsResponseCatalog.countsAsProviderFailure(ApnsResponseCatalog.TOO_MANY_REQUESTS, 429))
                .isFalse();
        assertThat(ApnsResponseCatalog.countsAsProviderFailure(ApnsResponseCatalog.EXPIRED_PROVIDER_TOKEN, 403))
                .isTrue();
        assertThat(ApnsResponseCatalog.countsAsProviderFailure(ApnsResponseCatalog.SERVICE_UNAVAILABLE, 503))
                .isTrue();
    }

    @Test
    @DisplayName("PU-06: a refused provider token is re-signed before the next call")
    void reSignsTheProviderToken() {
        assertThat(ApnsResponseCatalog.invalidatesProviderToken(ApnsResponseCatalog.EXPIRED_PROVIDER_TOKEN))
                .isTrue();
        assertThat(ApnsResponseCatalog.invalidatesProviderToken(ApnsResponseCatalog.INVALID_PROVIDER_TOKEN))
                .isTrue();
        assertThat(ApnsResponseCatalog.invalidatesProviderToken(ApnsResponseCatalog.UNREGISTERED))
                .isFalse();
    }

    @Test
    @DisplayName("PU-08: an answer without a readable reason is still recorded, as Unspecified")
    void namesAnUnreadableAnswer() {
        assertThat(ApnsResponseCatalog.describe(null, 400))
                .contains(ApnsResponseCatalog.UNSPECIFIED)
                .contains("400");
        assertThat(ApnsResponseCatalog.classify(null, 400)).isEqualTo(ErrorClass.NON_RETRYABLE);
    }
}
