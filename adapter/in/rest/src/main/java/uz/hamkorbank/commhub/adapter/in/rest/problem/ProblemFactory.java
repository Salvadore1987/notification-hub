package uz.hamkorbank.commhub.adapter.in.rest.problem;

import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/**
 * Builds the {@code application/problem+json} documents of IR-01 (RFC 9457).
 *
 * <p>One place builds them so every error of the API looks the same: {@code type}, {@code title} and
 * {@code status} from the catalogue, {@code detail} in human words, and the extension member
 * {@code code} that callers actually branch on. {@code detail} is free text and may change; the code
 * is the contract.
 */
@Component
public class ProblemFactory {

    /** Extension member holding the machine-readable code of IR-01. */
    public static final String CODE = "code";

    /** Extension member naming the field of the inbound document that was refused. */
    public static final String FIELD = "field";

    /** Extension member carrying the identifier of the message a code refers to. */
    public static final String MESSAGE_ID = "messageId";

    public ProblemDetail of(ProblemType type, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(type.status(), detail);
        problem.setType(type.type());
        problem.setTitle(type.title());
        problem.setProperty(CODE, type.name());
        return problem;
    }

    /** As {@link #of(ProblemType, String)} plus the field pointer of a contract violation. */
    public ProblemDetail ofField(ProblemType type, String detail, String field) {
        ProblemDetail problem = of(type, detail);
        if (field != null) {
            problem.setProperty(FIELD, field);
        }
        return problem;
    }
}
