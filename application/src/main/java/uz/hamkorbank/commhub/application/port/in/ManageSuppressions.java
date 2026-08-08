package uz.hamkorbank.commhub.application.port.in;

import uz.hamkorbank.commhub.application.dto.SuppressionView;
import uz.hamkorbank.commhub.application.port.in.command.ReleaseSuppressionCommand;
import uz.hamkorbank.commhub.application.port.in.command.SuppressAddressCommand;
import uz.hamkorbank.commhub.application.port.in.command.SuppressClientCommand;

/**
 * Administration of the suppression list (FR-5.1, FR-7.1, §11.2).
 *
 * <p>One interface per aggregate, as with the routing configuration and the template catalogue: these
 * operations share a transaction and an audit entry, and adding somebody to the list is precisely the kind
 * of change FR-7.3 wants attributed to a person.
 *
 * <p>Rejections here are exceptions, not verdicts (IR-01 is about the pipeline): listing an address twice
 * is {@code ConfigurationConflictException} (409) and releasing an entry that is not there is
 * {@code NotFoundException} (404).
 *
 * <p>Raw addresses enter through the commands and never leave: the service hashes them and stores the hash
 * (DB-04), so a listing can tell an operator that <em>an</em> address is suppressed and its reason, but the
 * table cannot hand anybody the Bank's phone numbers.
 */
public interface ManageSuppressions {

    /** Bans one address on one channel; the address is hashed before it is stored (FR-5.1, DB-04). */
    SuppressionView suppressAddress(SuppressAddressCommand command);

    /** Bans a whole client, on one channel or on all of them (FR-5.1). */
    SuppressionView suppressClient(SuppressClientCommand command);

    /** Removes an entry — an opt-in after an opt-out, a bounce that turned out to be transient (FR-5.1). */
    void release(ReleaseSuppressionCommand command);
}
