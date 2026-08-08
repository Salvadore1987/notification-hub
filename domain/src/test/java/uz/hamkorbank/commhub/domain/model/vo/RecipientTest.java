package uz.hamkorbank.commhub.domain.model.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.PushPlatform;

/** Channel-independent addressing block of the envelope (MP-01, PU-09, DB-04). */
class RecipientTest {

    private static final Msisdn MSISDN = Msisdn.of("998901234567");
    private static final EmailAddress EMAIL = EmailAddress.of("ivan@hamkorbank.uz");

    @Test
    @DisplayName("a recipient knows which channels it can be reached on")
    void reportsReachableChannels() {
        // Arrange
        Recipient recipient = new Recipient(
                ClientId.of("client-1"),
                MSISDN,
                EMAIL,
                List.of(
                        PushToken.of("token-android", PushPlatform.ANDROID),
                        PushToken.of("token-ios", PushPlatform.IOS)));

        // Act + Assert
        assertThat(recipient.reachableChannels()).containsExactly(Channel.SMS, Channel.EMAIL, Channel.PUSH);
        assertThat(recipient.hasAddressFor(Channel.SMS)).isTrue();
        assertThat(recipient.pushTokensFor(PushPlatform.IOS)).hasSize(1);
        assertThat(recipient.clientIdOptional()).contains(ClientId.of("client-1"));
    }

    @Test
    @DisplayName("a single-address recipient is reachable on that channel only")
    void singleAddressRecipient() {
        // Act
        Recipient smsOnly = Recipient.ofMsisdn(MSISDN);

        // Assert
        assertThat(smsOnly.reachableChannels()).containsExactly(Channel.SMS);
        assertThat(smsOnly.hasAddressFor(Channel.EMAIL)).isFalse();
        assertThat(smsOnly.hasAddressFor(Channel.PUSH)).isFalse();
        assertThat(Recipient.ofEmail(EMAIL).reachableChannels()).containsExactly(Channel.EMAIL);
        assertThat(Recipient.ofPushTokens(List.of(PushToken.of("t", PushPlatform.WEB)))
                        .reachableChannels())
                .containsExactly(Channel.PUSH);
    }

    @Test
    @DisplayName("a recipient without any address or client id is rejected")
    void rejectsEmptyRecipient() {
        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> new Recipient(null, null, null, List.of()))
                .withMessageContaining("at least one address");
    }

    @Test
    @DisplayName("DB-04: the description masks every address")
    void masksEveryAddress() {
        // Arrange
        Recipient recipient = new Recipient(
                ClientId.of("client-1"), MSISDN, EMAIL, List.of(PushToken.of("abcdef123456", PushPlatform.ANDROID)));

        // Act
        String masked = recipient.masked();

        // Assert
        assertThat(masked).contains("99890***4567", "i***n@hamkorbank.uz", "pushTokens=1");
        assertThat(masked).doesNotContain("998901234567", "abcdef123456");
    }

    @Test
    @DisplayName("push tokens are masked down to their suffix")
    void masksPushTokens() {
        // Act
        PushToken token = PushToken.of("abcdefghij123456", PushPlatform.IOS);

        // Assert
        assertThat(token.masked()).isEqualTo("IOS:***123456");
        assertThat(PushToken.of("ab", PushPlatform.WEB).masked()).isEqualTo("WEB:***ab");
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> PushToken.of("x".repeat(PushToken.MAX_LENGTH + 1), PushPlatform.IOS));
    }
}
