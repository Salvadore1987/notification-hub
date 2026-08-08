package uz.hamkorbank.commhub.adapter.in.rest.security;

import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * "A stream sees only its own data" (SEC-01), enforced where the stream is known.
 *
 * <p>Not a filter and not a rule in the security configuration, because the stream is in the body of the
 * submission and in a query parameter of the lookup — the request path does not name it. Every entry
 * point that learns a stream id asks here before doing anything with it, including reading: a status
 * lookup by an external id is a lookup in someone's data.
 *
 * <p>The refusal is a 403 and deliberately not a 404: the caller authenticated successfully and asked
 * for something that exists but is not theirs, and telling them it does not exist would be a lie that
 * makes an operational problem — a token issued with the wrong stream claim — look like a bad request.
 */
@Component
public class StreamAccessGuard {

    private final AuthenticatedCaller caller;

    public StreamAccessGuard(AuthenticatedCaller caller) {
        this.caller = Guard.notNull(caller, "caller");
    }

    /**
     * @throws StreamAccessDeniedException when the authenticated caller is not entitled to this stream
     */
    public void check(StreamId streamId) {
        check(streamId == null ? null : streamId.value());
    }

    public void check(String streamId) {
        if (streamId == null || streamId.isBlank() || caller.mayUseStream(streamId)) {
            return;
        }
        throw new StreamAccessDeniedException(streamId);
    }
}
