package uz.hamkorbank.commhub.adapter.out.provider.smsgate;

import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * The resolved {@code login} and {@code key} of one request (§9.2, SG-04).
 *
 * <p>SMS Gate authenticates by putting both into every request body, so unlike a Basic auth header
 * they cannot be kept out of the payload. That makes two rules non-negotiable in this package: a
 * request body is never logged, and this record never renders its contents — hence the
 * {@link #toString()} override, which is what a stray {@code log.debug("{}", credentials)} would
 * otherwise print into the Bank's log store.
 */
public record SmsGateCredentials(String login, String key) {

    public SmsGateCredentials {
        Guard.notBlank(login, "SmsGateCredentials.login");
        Guard.notBlank(key, "SmsGateCredentials.key");
    }

    @Override
    public String toString() {
        return "SmsGateCredentials[login=***, key=***]";
    }
}
