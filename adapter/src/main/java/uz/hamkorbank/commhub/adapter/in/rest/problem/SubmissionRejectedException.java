package uz.hamkorbank.commhub.adapter.in.rest.problem;

import uz.hamkorbank.commhub.application.dto.SubmitMessageResult;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * A submission came back refused and the REST caller has to be told why (IR-01).
 *
 * <p>A rejection is a verdict, not a failure — the pipeline recorded it and, where a message was
 * created, persisted it. This exception exists only to carry that verdict from the controller to the
 * handler that renders it, which is why it holds the whole result: the reason becomes the code, and
 * the message identifier stays in the document so the caller can poll the status of the message it
 * just had refused.
 */
public class SubmissionRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient SubmitMessageResult result;

    public SubmissionRejectedException(SubmitMessageResult result) {
        super("submission rejected: " + Guard.notNull(result, "result").status());
        Guard.isTrue(!result.isAccepted(), "SubmissionRejectedException requires a refused result");
        this.result = result;
    }

    public SubmitMessageResult result() {
        return result;
    }
}
