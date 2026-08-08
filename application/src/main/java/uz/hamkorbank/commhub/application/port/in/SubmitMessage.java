package uz.hamkorbank.commhub.application.port.in;

import uz.hamkorbank.commhub.application.dto.SubmitMessageResult;
import uz.hamkorbank.commhub.application.port.in.command.SubmitMessageCommand;

/**
 * Accepts one message from a source system (FR-1.1, FR-1.4, FR-1.5).
 *
 * <p>Runs the channel-agnostic head of the pipeline: validation → deduplication → traffic class →
 * template → segmentation → filters → routing → persistence with the outbox event (SRS §5.1, AD-03).
 * Nothing is sent here; the message is left in a status the sending saga picks up (AD-04).
 */
public interface SubmitMessage {

    SubmitMessageResult submit(SubmitMessageCommand command);
}
