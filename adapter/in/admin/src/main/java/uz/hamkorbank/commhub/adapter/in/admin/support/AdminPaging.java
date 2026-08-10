package uz.hamkorbank.commhub.adapter.in.admin.support;

import uz.hamkorbank.commhub.adapter.in.contract.InboundContractException;

/**
 * Server-side paging parameters of the admin lists (UI-03).
 *
 * <p>The ceiling belongs to the query record of each use case — it knows what its own page costs — so
 * all this does is turn an out-of-range request into the refusal of IR-01 rather than letting a
 * {@code Guard} deep in the application throw an unhandled exception at the edge. A caller asking for
 * ten thousand rows is asking for the export, and the export has its own endpoint.
 */
public final class AdminPaging {

    private AdminPaging() {}

    /** Page size, defaulted and bounded by what the use case allows. */
    public static int limit(Integer requested, int defaultLimit, int maxLimit) {
        if (requested == null) {
            return defaultLimit;
        }
        if (requested < 1) {
            throw InboundContractException.invalid("limit", "must be at least 1");
        }
        if (requested > maxLimit) {
            throw InboundContractException.invalid("limit", "must not exceed " + maxLimit);
        }
        return requested;
    }

    public static int offset(Integer requested) {
        if (requested == null) {
            return 0;
        }
        if (requested < 0) {
            throw InboundContractException.invalid("offset", "must not be negative");
        }
        return requested;
    }
}
