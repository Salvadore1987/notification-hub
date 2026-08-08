package uz.hamkorbank.commhub.application.port.in;

import uz.hamkorbank.commhub.application.dto.SubmitMessageResult;
import uz.hamkorbank.commhub.application.port.in.command.SendTestMessageCommand;

/**
 * Sends one message to verify a channel or provider configuration (FR-7.4, PU-13).
 *
 * <p>A real send: the message traverses the whole pipeline of §5.1, is delivered by the same adapters
 * and produces the same statuses. A test that took a shortcut would confirm a configuration the traffic
 * does not use.
 *
 * <p>What differs is one flag. The message is marked TEST, which keeps it out of the business figures
 * (the metrics carry it as a dimension), tells the push adapters to use the sandbox environment of PU-13
 * and makes the send findable afterwards as what it was.
 */
public interface SendTestMessage {

    SubmitMessageResult send(SendTestMessageCommand command);
}
