package uz.hamkorbank.commhub.adapter.out.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.adapter.out.provider.playmobile.PlaymobileErrorCatalog;
import uz.hamkorbank.commhub.adapter.out.provider.smsgate.SmsGateResponseCatalog;
import uz.hamkorbank.commhub.adapter.out.provider.smsgate.SmsGateStatusCatalog;
import uz.hamkorbank.commhub.domain.model.type.ErrorClass;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;

/**
 * Every code the provider documentation lists is one the Hub recognises (QA-04, PR-04, §18.1, §18.2).
 *
 * <p>The catalogue tests next to each adapter check the codes that carry a decision — 102 opens the
 * breaker, 20 retires an address, 27 is worth retrying. This one checks the rest of the table, which is
 * the half that goes wrong quietly: a code nobody wrote a case for still gets an answer, and that answer
 * is "undocumented", which is right for a code Playmobile invented tomorrow and wrong for one that has
 * been in §18.1 since the integration was agreed.
 *
 * <p>The lists below are transcribed from the appendices. That is the point — they are the
 * documentation, and a test that read them from the catalogue would only prove the catalogue agrees with
 * itself.
 */
class ProviderDocumentationContractTest {

    /** §18.1: every {@code error_code} Playmobile answers HTTP 400 with. */
    private static final List<String> PLAYMOBILE_CODES = List.of(
            "100", "101", "102", "103", "104", "105", "202", "204", "205", "206", "301", "302", "303", "304", "305",
            "306", "401", "402", "403", "404", "405", "406", "407", "408", "410", "411");

    /** §18.2: every response code of {@code /api/v2/send}, success included. */
    private static final List<String> SMS_GATE_RESPONSE_CODES = List.of(
            "0", "1", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23", "24", "25",
            "26", "27");

    /** §18.2: every FEEDBACK code of a delivery report. */
    private static final List<String> SMS_GATE_FEEDBACK_CODES = List.of("0", "1", "2", "3", "4", "5", "6", "7");

    /** §18.2: the states a batch element comes back in. */
    private static final List<String> SMS_GATE_ITEM_STATES =
            List.of("ok", "duplicate", "blacklist", "unknown operator", "format error", "empty", "empty text");

    @Test
    @DisplayName("§18.1: every documented Playmobile code is recognised and described")
    void everyPlaymobileCodeIsKnown() {
        // Arrange + Act + Assert
        for (String code : PLAYMOBILE_CODES) {
            assertThat(PlaymobileErrorCatalog.isKnown(code))
                    .as("§18.1 lists code %s", code)
                    .isTrue();
            assertThat(PlaymobileErrorCatalog.describe(code, null))
                    .as("code %s has a description of its own", code)
                    .doesNotContain("undocumented");
        }
    }

    @Test
    @DisplayName("§18.1: exactly one code is retryable and exactly one opens the breaker")
    void playmobileClassificationMatchesTheTable() {
        // Arrange
        List<String> retryable = PLAYMOBILE_CODES.stream()
                .filter(code -> PlaymobileErrorCatalog.classify(code) == ErrorClass.RETRYABLE)
                .toList();
        List<String> blocking = PLAYMOBILE_CODES.stream()
                .filter(code -> PlaymobileErrorCatalog.classify(code) == ErrorClass.BLOCKING)
                .toList();

        // Act + Assert — 100 is Playmobile's own transient failure, 102 Account lock fails every message
        // identically until somebody unlocks the account, and everything else is about the document
        assertThat(retryable).containsExactly("100");
        assertThat(blocking).containsExactly("102");
    }

    @Test
    @DisplayName("§18.2: every documented response code is described and classified")
    void everySmsGateResponseCodeIsKnown() {
        // Arrange + Act + Assert
        for (String code : SMS_GATE_RESPONSE_CODES) {
            assertThat(SmsGateResponseCatalog.describe(code))
                    .as("§18.2 describes code %s", code)
                    .doesNotContain("undocumented");
            assertThat(SmsGateResponseCatalog.classify(code))
                    .as("code %s is classified", code)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("§18.2: the authentication codes 10–13 are blocking, 27 is retryable, 0 is success")
    void smsGateClassificationMatchesTheTable() {
        // Arrange
        List<String> blocking = SMS_GATE_RESPONSE_CODES.stream()
                .filter(code -> SmsGateResponseCatalog.classify(code) == ErrorClass.BLOCKING)
                .toList();

        // Act + Assert
        assertThat(blocking).containsExactly("10", "11", "12", "13");
        assertThat(SmsGateResponseCatalog.isSuccess("0")).isTrue();
        assertThat(SmsGateResponseCatalog.classify(SmsGateResponseCatalog.SERVER_ERROR))
                .isEqualTo(ErrorClass.RETRYABLE);
        // 1 is the provider's own anti-spam ceiling: the message goes elsewhere, the provider is fine
        assertThat(SmsGateResponseCatalog.classify(SmsGateResponseCatalog.SPAM_LIMIT))
                .isEqualTo(ErrorClass.RETRYABLE);
        assertThat(SmsGateResponseCatalog.countsAsProviderFailure(SmsGateResponseCatalog.SPAM_LIMIT))
                .isFalse();
        assertThat(SmsGateResponseCatalog.invalidatesRecipient(SmsGateResponseCatalog.BLACKLISTED))
                .isTrue();
    }

    @Test
    @DisplayName("§18.2: every FEEDBACK code maps onto §6.3, and code 6 deliberately onto nothing")
    void everyFeedbackCodeMapsOntoACanonicalStatus() {
        // Arrange + Act + Assert
        for (String code : SMS_GATE_FEEDBACK_CODES) {
            Optional<MessageStatus> canonical = SmsGateStatusCatalog.canonical(code);
            if (SmsGateStatusCatalog.UNKNOWN.equals(code)) {
                // "Unknown" is not an outcome; SG-03's reconciliation is what resolves it
                assertThat(canonical).as("code 6 applies as nothing").isEmpty();
            } else {
                assertThat(canonical).as("§18.2 maps FEEDBACK code %s", code).isPresent();
            }
            assertThat(SmsGateStatusCatalog.describe(code))
                    .as("code %s is described", code)
                    .doesNotContain("undocumented");
        }
    }

    @Test
    @DisplayName("SG-02: every documented batch item state folds onto the response code table")
    void everyItemStateFoldsOntoACode() {
        // Arrange + Act + Assert
        for (String state : SMS_GATE_ITEM_STATES) {
            String code = SmsGateResponseCatalog.codeOfItemState(state);
            assertThat(code).as("state '%s' has a code", state).isNotNull();
            if ("ok".equals(state)) {
                assertThat(SmsGateResponseCatalog.isSuccess(code)).isTrue();
            } else {
                assertThat(SmsGateResponseCatalog.isSuccess(code))
                        .as("state '%s' is a refusal", state)
                        .isFalse();
            }
        }
    }
}
