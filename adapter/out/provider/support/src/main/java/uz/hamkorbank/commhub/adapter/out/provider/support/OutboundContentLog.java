package uz.hamkorbank.commhub.adapter.out.provider.support;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.port.out.provider.EmailSubmission;
import uz.hamkorbank.commhub.application.port.out.provider.PushSubmission;
import uz.hamkorbank.commhub.application.port.out.provider.SmsSubmission;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * What is actually being sent, in clear, for the local stand only (ADR-0041, SEC-06).
 *
 * <p>Every other log line in this layer is masked at the point of writing — that is the rule of
 * {@link Masking} and it does not change: an OTP send must not leave its password in an operational
 * log. What a developer on the stand needs is the opposite, and the two are reconciled by making the
 * plaintext trace a separate, additive line behind a switch that is off everywhere it matters
 * ({@link ContentLogProperties}) instead of by weakening the masked lines that ship.
 *
 * <p>It writes under its own logger name — {@value #LOGGER_NAME} — rather than under the calling
 * adapter's, so it is one line to enable, one line to grep and one line to notice in a place it does
 * not belong. Nothing is written at all when the switch is off: the flag is read once at startup and
 * checked before the arguments are touched, so the trace costs a field read on the OTP path.
 *
 * <p>One thing it does not control: in a contour that renders structured JSON logs the
 * {@code PiiMaskingJsonCustomizer} safety net still masks numbers and addresses in the rendered
 * document. That is intentional — this switch is meant for the plain console of a local run, and
 * turning it on in a contour must not be enough to defeat OBS-03 on its own.
 */
@Component
public class OutboundContentLog {

    /** Logger of the trace, deliberately not a class name — it is a channel, not a component. */
    public static final String LOGGER_NAME = "uz.hamkorbank.commhub.outbound.content";

    private static final Logger LOG = LoggerFactory.getLogger(LOGGER_NAME);

    private final boolean enabled;

    public OutboundContentLog(ContentLogProperties properties) {
        this.enabled = Guard.notNull(properties, "properties").enabled();
    }

    /** The trace switched off, for tests and for anything assembling the framework by hand. */
    public static OutboundContentLog disabled() {
        return new OutboundContentLog(ContentLogProperties.disabled());
    }

    /** Whether the trace is on; the adapters do not need to ask, the {@code record} methods check. */
    public boolean isEnabled() {
        return enabled;
    }

    /** One SMS on its way to a provider: number and text as they will be sent. */
    public void record(SmsSubmission submission) {
        if (!enabled || submission == null) {
            return;
        }
        LOG.info(
                "SMS to {} via {} (message {}): {}",
                submission.recipient().value(),
                submission.provider().code().value(),
                submission.messageId().value(),
                submission.content().text());
    }

    /** The same for a chunk handed to a provider that takes them in bulk (§9.1, §9.2). */
    public void recordAll(List<SmsSubmission> submissions) {
        if (!enabled || submissions == null) {
            return;
        }
        submissions.forEach(this::record);
    }

    /**
     * One email on its way to the relay.
     *
     * <p>The HTML alternative is reported by its size only: it is a rendering of the text body rather
     * than a second wording, and a page of markup in a console line hides the very thing that was
     * being looked for.
     */
    public void record(EmailSubmission submission) {
        if (!enabled || submission == null) {
            return;
        }
        LOG.info(
                "EMAIL to {} via {} (message {}): subject={} text={} html={}",
                submission.recipient().value(),
                submission.provider().code().value(),
                submission.messageId().value(),
                submission.content().subject(),
                submission.content().textBody(),
                htmlSize(submission.content().htmlBody()));
    }

    /** One push on its way to a platform, addressed to one device token (PU-06). */
    public void record(PushSubmission submission) {
        if (!enabled || submission == null) {
            return;
        }
        LOG.info(
                "PUSH to {} via {} (message {}): title={} body={} data={}",
                submission.token().value(),
                submission.provider().code().value(),
                submission.messageId().value(),
                submission.content().title(),
                submission.content().body(),
                submission.content().data());
    }

    private static String htmlSize(String html) {
        return html == null || html.isBlank() ? "-" : "[" + html.length() + " chars]";
    }
}
