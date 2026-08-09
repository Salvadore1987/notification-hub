package uz.hamkorbank.commhub.adapter.out.provider.smtp;

import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * The resolved relay credentials of one connection (SEC-04).
 *
 * <p>Same rule as {@code SmsGateCredentials}: the record never renders its contents, so a stray
 * {@code log.debug("{}", credentials)} cannot put an SMTP password into the Bank's log store (PR-03).
 */
public record SmtpCredentials(String username, String password) {

    public SmtpCredentials {
        Guard.notBlank(username, "SmtpCredentials.username");
        Guard.notBlank(password, "SmtpCredentials.password");
    }

    @Override
    public String toString() {
        return "SmtpCredentials[username=***, password=***]";
    }
}
