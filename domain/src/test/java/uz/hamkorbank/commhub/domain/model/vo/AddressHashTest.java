package uz.hamkorbank.commhub.domain.model.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;
import uz.hamkorbank.commhub.domain.model.type.PushPlatform;

/** Suppression lookups work on hashes, never on raw addresses (FR-5.1, DB-04). */
class AddressHashTest {

    @Test
    @DisplayName("the same address always yields the same hash")
    void hashingIsDeterministic() {
        // Act
        AddressHash first = AddressHash.ofMsisdn(Msisdn.of("998901234567"));
        AddressHash second = AddressHash.of("998901234567");

        // Assert
        assertThat(first).isEqualTo(second);
        assertThat(first.value()).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("hashing is case-insensitive and covers every address type")
    void hashesEveryAddressType() {
        // Act
        AddressHash email = AddressHash.ofEmail(EmailAddress.of("Ivan@Hamkorbank.uz"));
        AddressHash pushToken = AddressHash.ofPushToken(PushToken.of("device-token", PushPlatform.ANDROID));

        // Assert
        assertThat(email).isEqualTo(AddressHash.of("ivan@hamkorbank.uz"));
        assertThat(pushToken).isEqualTo(AddressHash.of("DEVICE-TOKEN"));
    }

    @Test
    @DisplayName("different addresses yield different hashes")
    void differentAddressesDiffer() {
        // Act + Assert
        assertThat(AddressHash.of("998901234567")).isNotEqualTo(AddressHash.of("998901234568"));
    }

    @Test
    @DisplayName("only a 64-character lower-case hex value is a valid hash")
    void rejectsNonHashValues() {
        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> new AddressHash("not-a-hash"));
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> AddressHash.of(" "));
    }
}
