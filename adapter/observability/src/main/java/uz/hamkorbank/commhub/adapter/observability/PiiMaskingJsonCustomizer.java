package uz.hamkorbank.commhub.adapter.observability;

import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Masks personal data in the rendered log document (OBS-03, SEC-05).
 *
 * <p>Registered through {@code logging.structured.json.customizer}, so it is instantiated by the logging
 * system and not by the application context — which is exactly why it is useful: it is in place before
 * the first bean is created and stays in place if the context fails to start, and those first lines are
 * the ones nobody reviews for what they print.
 *
 * <p>Only members named {@code message} are processed — the log message itself and, in the ECS layout,
 * {@code error.message}. Identifiers in the MDC are left alone: they are ids, not personal data, and
 * masking them would break the very correlation OBS-03 asks for.
 */
public class PiiMaskingJsonCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {

    private static final String MESSAGE_MEMBER = "message";

    @Override
    public void customize(JsonWriter.Members<Object> members) {
        members.applyingValueProcessor(JsonWriter.ValueProcessor.of(String.class, LogMasking::mask)
                .whenHasPath(path -> MESSAGE_MEMBER.equals(path.name())));
    }
}
