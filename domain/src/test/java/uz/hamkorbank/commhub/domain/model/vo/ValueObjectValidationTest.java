package uz.hamkorbank.commhub.domain.model.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;
import uz.hamkorbank.commhub.domain.support.UuidV7;

/** Format invariants of the envelope identifiers (FR-1.4, FR-1.5, §9.1). */
class ValueObjectValidationTest {

    @Test
    @DisplayName("newId of every aggregate identifier produces a UUIDv7 (project rule, DB-02)")
    void identifiersAreTimeOrdered() {
        // Act + Assert
        assertThat(UuidV7.isUuidV7(MessageId.newId().value())).isTrue();
        assertThat(UuidV7.isUuidV7(BatchId.newId().value())).isTrue();
        assertThat(UuidV7.isUuidV7(ProviderId.newId().value())).isTrue();
        assertThat(UuidV7.isUuidV7(TemplateId.newId().value())).isTrue();
        assertThat(UuidV7.isUuidV7(TemplateVersionId.newId().value())).isTrue();
        assertThat(UuidV7.isUuidV7(AttemptId.newId().value())).isTrue();
        assertThat(UuidV7.isUuidV7(SuppressionEntryId.newId().value())).isTrue();
        assertThat(UuidV7.isUuidV7(RoutingPolicyId.newId().value())).isTrue();
    }

    @Test
    @DisplayName("MessageId exposes the creation instant of a UUIDv7 and hides it for other versions")
    void messageIdExposesItsCreationInstant() {
        // Arrange
        MessageId timeOrdered = MessageId.newId();
        MessageId random = MessageId.of(UUID.randomUUID());

        // Act + Assert
        assertThat(timeOrdered.createdAt()).isPresent();
        assertThat(random.createdAt()).isEmpty();
        assertThat(MessageId.fromString(timeOrdered.value().toString())).isEqualTo(timeOrdered);
        assertThat(timeOrdered).hasToString(timeOrdered.value().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Mobile-App", "a", "-crm", "поток", "stream with spaces"})
    @DisplayName("StreamId accepts only lower-case codes")
    void streamIdRejectsInvalidCodes(String candidate) {
        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> StreamId.of(candidate));
    }

    @Test
    @DisplayName("dedup key defaults to streamId:externalMessageId (FR-1.5)")
    void dedupKeyIsDerivedFromTheExternalId() {
        // Arrange
        StreamId streamId = StreamId.of("mobile-app");
        ExternalMessageId externalId = ExternalMessageId.of("abc0000001");

        // Act
        DedupKey dedupKey = DedupKey.forExternalId(streamId, externalId);

        // Assert
        assertThat(dedupKey.value()).isEqualTo("mobile-app:abc0000001");
        assertThat(DedupKey.of("explicit-key")).hasToString("explicit-key");
    }

    @Test
    @DisplayName("external message id and correlation id reject blank and oversized values")
    void envelopeIdentifiersEnforceLength() {
        // Arrange
        String tooLong = "x".repeat(ExternalMessageId.MAX_LENGTH + 1);

        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> ExternalMessageId.of(" "));
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> ExternalMessageId.of(tooLong));
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> CorrelationId.of(""));
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> ClientId.of(""));
        assertThat(CorrelationId.newId().value()).isNotBlank();
    }

    @Test
    @DisplayName("provider code and adapter type are normalised free-form strings (AR-04)")
    void providerCodesAreNormalised() {
        // Act
        ProviderCode code = ProviderCode.of("playmobile");
        AdapterType adapterType = AdapterType.of("playmobile-http");

        // Assert
        assertThat(code.value()).isEqualTo("PLAYMOBILE");
        assertThat(adapterType).hasToString("playmobile-http");
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> ProviderCode.of("bad code"));
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> AdapterType.of("BAD_TYPE"));
    }

    @Test
    @DisplayName("template code is upper-cased and validated")
    void templateCodeIsNormalised() {
        // Act + Assert
        assertThat(TemplateCode.of("otp_login").value()).isEqualTo("OTP_LOGIN");
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> TemplateCode.of("otp login"));
    }

    @Test
    @DisplayName("provider message id keeps the provider-side identifier within 64 characters")
    void providerMessageIdEnforcesLength() {
        // Act + Assert
        assertThat(ProviderMessageId.of("HB00000000000000001")).hasToString("HB00000000000000001");
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> ProviderMessageId.of("x".repeat(ProviderMessageId.MAX_LENGTH + 1)));
    }
}
