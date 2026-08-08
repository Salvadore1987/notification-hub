package uz.hamkorbank.commhub.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static uz.hamkorbank.commhub.domain.DomainFixtures.NOW;
import static uz.hamkorbank.commhub.domain.DomainFixtures.msisdn;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.SuppressionReason;
import uz.hamkorbank.commhub.domain.model.vo.AddressHash;
import uz.hamkorbank.commhub.domain.model.vo.ClientId;
import uz.hamkorbank.commhub.domain.model.vo.SuppressionEntryId;

/** Suppression entries are matched by hash, per channel or for a whole client (FR-5.1, DB-04). */
class SuppressionEntryTest {

    private static final AddressHash ADDRESS = AddressHash.ofMsisdn(msisdn());
    private static final ClientId CLIENT = ClientId.of("client-1");

    @Test
    @DisplayName("FR-5.1: an address entry blocks only its channel")
    void addressEntryIsChannelScoped() {
        // Act
        SuppressionEntry entry = SuppressionEntry.forAddress(
                SuppressionEntryId.newId(), Channel.SMS, ADDRESS, SuppressionReason.OPT_OUT, NOW, "operator-1");

        // Assert
        assertThat(entry.matchesAddress(Channel.SMS, ADDRESS)).isTrue();
        assertThat(entry.matchesAddress(Channel.EMAIL, ADDRESS)).isFalse();
        assertThat(entry.matchesAddress(Channel.SMS, AddressHash.of("998901234568")))
                .isFalse();
        assertThat(entry.matchesClient(Channel.SMS, CLIENT)).isFalse();
        assertThat(entry.isActiveAt(NOW)).isTrue();
        assertThat(entry.reason()).isEqualTo(SuppressionReason.OPT_OUT);
        assertThat(entry.channel()).contains(Channel.SMS);
        assertThat(entry.addressHash()).contains(ADDRESS);
        assertThat(entry.clientId()).isEmpty();
        assertThat(entry.createdBy()).contains("operator-1");
        assertThat(entry.createdAt()).isEqualTo(NOW);
        assertThat(entry.validUntil()).isEmpty();
    }

    @Test
    @DisplayName("FR-5.1: an entry without a channel blocks every channel of the client")
    void clientEntryWithoutChannelCoversEverything() {
        // Act
        SuppressionEntry entry = SuppressionEntry.forClient(
                SuppressionEntryId.newId(), null, CLIENT, SuppressionReason.COMPLAINT, NOW, "operator-1");

        // Assert
        assertThat(entry.matchesClient(Channel.SMS, CLIENT)).isTrue();
        assertThat(entry.matchesClient(Channel.PUSH, CLIENT)).isTrue();
        assertThat(entry.matchesClient(Channel.EMAIL, ClientId.of("client-2"))).isFalse();
        assertThat(entry.coversChannel(Channel.EMAIL)).isTrue();
        assertThat(entry.matchesAddress(Channel.SMS, ADDRESS)).isFalse();
    }

    @Test
    @DisplayName("a temporary entry expires and can be made permanent again")
    void temporaryEntriesExpire() {
        // Arrange
        SuppressionEntry entry = SuppressionEntry.forAddress(
                SuppressionEntryId.newId(),
                Channel.EMAIL,
                ADDRESS,
                SuppressionReason.HARD_BOUNCE,
                NOW,
                "bounce-processor");

        // Act
        entry.expireAt(NOW.plus(Duration.ofDays(30)));

        // Assert
        assertThat(entry.isActiveAt(NOW.plus(Duration.ofDays(29)))).isTrue();
        assertThat(entry.isActiveAt(NOW.plus(Duration.ofDays(30)))).isFalse();
        assertThat(entry.validUntil()).contains(NOW.plus(Duration.ofDays(30)));

        entry.makePermanent();
        assertThat(entry.isActiveAt(NOW.plus(Duration.ofDays(3650)))).isTrue();
    }

    @Test
    @DisplayName("an entry needs an address or a client, and an expiry in the future")
    void invariantsAreEnforced() {
        // Arrange
        SuppressionEntry entry = SuppressionEntry.forClient(
                SuppressionEntryId.newId(),
                Channel.SMS,
                CLIENT,
                SuppressionReason.PROVIDER_BLACKLIST,
                NOW,
                "smsgate-feedback");

        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> entry.expireAt(NOW.minusSeconds(1)))
                .withMessageContaining("must be after createdAt");
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> SuppressionEntry.forAddress(
                        SuppressionEntryId.newId(), Channel.SMS, null, SuppressionReason.MANUAL, NOW, "operator"));
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> SuppressionEntry.forClient(
                        SuppressionEntryId.newId(), Channel.SMS, null, SuppressionReason.MANUAL, NOW, "operator"));
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> SuppressionEntry.forAddress(
                        SuppressionEntryId.newId(), Channel.SMS, ADDRESS, null, NOW, "operator"));
    }
}
