package uz.hamkorbank.commhub.adapter.in.admin.support;

import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.adapter.in.rest.security.AuthenticatedCaller;
import uz.hamkorbank.commhub.adapter.in.rest.security.Roles;
import uz.hamkorbank.commhub.adapter.out.provider.support.Masking;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * How much of a recipient address a given role is shown (§11.2 "Сообщения", DB-04, SEC-06).
 *
 * <p>§11.2 gives the message screens to {@code OPERATOR+} and to {@code VIEWER} with masked addresses,
 * which is a statement about degree rather than access: everyone who may open the screen sees the rows,
 * and only the people who have to act on an address see all of it. Somebody investigating a complaint
 * needs the number they were given; somebody watching a dashboard does not.
 *
 * <p>Masking happens here, on the way out, and not in the application layer — which knows nothing about
 * roles — and not in the search port, which has to compare full addresses to find anything. It reuses
 * the provider-side {@link Masking} so a number looks the same in the panel as in the log an operator
 * is comparing it against ({@code 99890***4567}).
 */
@Component
public class AdminMasking {

    private final AuthenticatedCaller caller;

    public AdminMasking(AuthenticatedCaller caller) {
        this.caller = Guard.notNull(caller, "caller");
    }

    /**
     * The address as this caller may see it.
     *
     * <p>The channel is not asked for: what the column holds decides how it is masked, and a row whose
     * address is a mailbox is masked as a mailbox whatever channel the message was submitted on.
     */
    public String recipient(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        if (showsFullAddresses()) {
            return address;
        }
        return address.indexOf('@') > 0 ? Masking.email(address) : Masking.msisdn(address);
    }

    /** Whether this caller is one of the roles §11.2 shows unmasked addresses to. */
    public boolean showsFullAddresses() {
        return caller.hasAnyRole(Roles.ADMIN, Roles.OPERATOR);
    }
}
