package uz.hamkorbank.commhub.adapter.out.provider.smtp;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderCallException;
import uz.hamkorbank.commhub.domain.model.type.ErrorClass;

/** The SMTP reply table: what is the relay's problem, what is the message's, and what kills an address. */
class SmtpResponseCatalogTest {

    @Test
    @DisplayName("PR-01: 4xx is the relay's problem, 5xx is the message's, auth is blocking")
    void classifiesReplyCodes() {
        // Act + Assert
        assertThat(SmtpResponseCatalog.classify("451")).isEqualTo(ErrorClass.RETRYABLE);
        assertThat(SmtpResponseCatalog.classify("421")).isEqualTo(ErrorClass.RETRYABLE);
        assertThat(SmtpResponseCatalog.classify("550")).isEqualTo(ErrorClass.NON_RETRYABLE);
        assertThat(SmtpResponseCatalog.classify("552")).isEqualTo(ErrorClass.NON_RETRYABLE);
        assertThat(SmtpResponseCatalog.classify("535")).isEqualTo(ErrorClass.BLOCKING);
        assertThat(SmtpResponseCatalog.classify("530")).isEqualTo(ErrorClass.BLOCKING);
        // Ответа не было вовсе: это всегда «попробуй ещё раз», иначе сообщение уедет в DLQ из-за сети.
        assertThat(SmtpResponseCatalog.classify(null)).isEqualTo(ErrorClass.RETRYABLE);
    }

    @Test
    @DisplayName("PR-01: a stream of rejected recipients never reaches the circuit breaker")
    void rejectionsAreNotProviderFailures() {
        // Act + Assert
        assertThat(SmtpResponseCatalog.countsAsProviderFailure("550")).isFalse();
        assertThat(SmtpResponseCatalog.countsAsProviderFailure("451")).isTrue();
        assertThat(SmtpResponseCatalog.countsAsProviderFailure("535")).isTrue();
    }

    @Test
    @DisplayName("EM-02: only an explicit 'no such mailbox' suppresses the address")
    void onlyBadMailboxSuppresses() {
        // Act + Assert
        assertThat(SmtpResponseCatalog.invalidatesRecipient("5.1.1")).isTrue();
        assertThat(SmtpResponseCatalog.invalidatesRecipient("5.1.2")).isTrue();
        // Переполненный ящик и отказ по политике — про сообщение, а не про адрес.
        assertThat(SmtpResponseCatalog.invalidatesRecipient("5.2.2")).isFalse();
        assertThat(SmtpResponseCatalog.invalidatesRecipient("5.7.1")).isFalse();
        assertThat(SmtpResponseCatalog.invalidatesRecipient(null)).isFalse();
    }

    @Test
    @DisplayName("the reply code and the enhanced status are read out of the exception chain")
    void readsCodesFromTheExceptionChain() {
        // Arrange
        MessagingException failure = new MessagingException(
                "Invalid Addresses", new MessagingException("550 5.1.1 <client@example.com>: User unknown"));

        // Act + Assert
        assertThat(SmtpResponseCatalog.replyCodeOf(failure)).contains("550");
        assertThat(SmtpResponseCatalog.enhancedStatusOf(failure)).contains("5.1.1");
    }

    @Test
    @DisplayName("PR-01: an authentication refusal opens the breaker, a timeout is a timeout")
    void buildsTheCallException() {
        // Act
        ProviderCallException auth = SmtpResponseCatalog.toCallException(new AuthenticationFailedException("nope"));
        ProviderCallException timeout = SmtpResponseCatalog.toCallException(
                new MessagingException("read timed out", new SocketTimeoutException("Read timed out")));

        // Assert
        assertThat(auth.isBlocking()).isTrue();
        assertThat(timeout.isTimeout()).isTrue();
        assertThat(timeout.errorClass()).isEqualTo(ErrorClass.RETRYABLE);
    }
}
