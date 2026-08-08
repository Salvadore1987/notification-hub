package uz.hamkorbank.commhub.adapter.out.provider.fcm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import uz.hamkorbank.commhub.domain.model.type.ErrorClass;

/** The FCM error table of PU-04, and the three decisions each answer drives (PR-01). */
class FcmErrorCatalogTest {

    @ParameterizedTest(name = "{0} (HTTP {1}) is {2}")
    @DisplayName("PU-04: every documented error code lands in the class the specification gives it")
    @CsvSource({
        "UNREGISTERED,404,NON_RETRYABLE",
        "NOT_FOUND,404,NON_RETRYABLE",
        "INVALID_ARGUMENT,400,NON_RETRYABLE",
        "SENDER_ID_MISMATCH,403,NON_RETRYABLE",
        "THIRD_PARTY_AUTH_ERROR,401,NON_RETRYABLE",
        "QUOTA_EXCEEDED,429,RETRYABLE",
        "UNAVAILABLE,503,RETRYABLE",
        "INTERNAL,500,RETRYABLE",
    })
    void classifiesTheDocumentedCodes(String code, int status, ErrorClass expected) {
        assertThat(FcmErrorCatalog.classify(code, status)).isEqualTo(expected);
    }

    @Test
    @DisplayName("PU-04: an uninstalled application retires the token; a malformed message does not")
    void retiresOnlyDeadTokens() {
        assertThat(FcmErrorCatalog.invalidatesToken(FcmErrorCatalog.UNREGISTERED, 404))
                .isTrue();
        assertThat(FcmErrorCatalog.invalidatesToken(FcmErrorCatalog.NOT_FOUND, 404))
                .isTrue();
        assertThat(FcmErrorCatalog.invalidatesToken(FcmErrorCatalog.INVALID_ARGUMENT, 400))
                .isFalse();
        assertThat(FcmErrorCatalog.invalidatesToken(FcmErrorCatalog.SENDER_ID_MISMATCH, 403))
                .isFalse();
    }

    @Test
    @DisplayName("PR-01: a campaign against dead tokens never counts against the health of FCM")
    void deadTokensAreNotAProviderFailure() {
        assertThat(FcmErrorCatalog.countsAsProviderFailure(FcmErrorCatalog.UNREGISTERED, 404))
                .isFalse();
        assertThat(FcmErrorCatalog.countsAsProviderFailure(FcmErrorCatalog.INVALID_ARGUMENT, 400))
                .isFalse();
        assertThat(FcmErrorCatalog.countsAsProviderFailure(FcmErrorCatalog.UNAVAILABLE, 503))
                .isTrue();
    }

    @Test
    @DisplayName("PR-01: being rate-limited is the Hub going too fast, not a provider that is unwell")
    void throttlingDoesNotOpenTheBreaker() {
        assertThat(FcmErrorCatalog.classify(FcmErrorCatalog.QUOTA_EXCEEDED, 429))
                .isEqualTo(ErrorClass.RETRYABLE);
        assertThat(FcmErrorCatalog.countsAsProviderFailure(FcmErrorCatalog.QUOTA_EXCEEDED, 429))
                .isFalse();
    }

    @Test
    @DisplayName("PU-01: refused credentials are blocking and drop the cached access token")
    void refusedCredentialsAreBlocking() {
        assertThat(FcmErrorCatalog.classify(null, 401)).isEqualTo(ErrorClass.BLOCKING);
        assertThat(FcmErrorCatalog.countsAsProviderFailure(null, 401)).isTrue();
        assertThat(FcmErrorCatalog.invalidatesAccessToken(null, 401)).isTrue();
    }

    @Test
    @DisplayName("PU-04: a 401 that names a message-level code is not a refusal of the Hub's credentials")
    void aMessageLevelCodeNeverOpensTheBreaker() {
        // SENDER_ID_MISMATCH and THIRD_PARTY_AUTH_ERROR arrive with 403/401 but describe the message.
        assertThat(FcmErrorCatalog.invalidatesAccessToken(FcmErrorCatalog.THIRD_PARTY_AUTH_ERROR, 401))
                .isFalse();
        assertThat(FcmErrorCatalog.countsAsProviderFailure(FcmErrorCatalog.SENDER_ID_MISMATCH, 403))
                .isFalse();
    }

    @Test
    @DisplayName("an unknown code is refused rather than retried — HTTP already said the request was rejected")
    void unknownCodesAreNonRetryable() {
        assertThat(FcmErrorCatalog.classify("SOMETHING_NEW", 400)).isEqualTo(ErrorClass.NON_RETRYABLE);
        assertThat(FcmErrorCatalog.describe(null, 400, "bad request"))
                .contains("400")
                .contains("bad request");
    }
}
