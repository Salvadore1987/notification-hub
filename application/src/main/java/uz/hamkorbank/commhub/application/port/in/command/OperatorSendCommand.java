package uz.hamkorbank.commhub.application.port.in.command;

import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.TemplateRef;
import uz.hamkorbank.commhub.domain.model.Timing;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.Recipient;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * One message an operator sends from the panel (ADR-0038, §11.2, FR-7.3).
 *
 * <p>There is no text field, and that is the design rather than an omission: content comes only from a
 * published template, so "the panel sent an unreviewed wording to customers" is a state that cannot be
 * reached without changing this record (FR-4.2).
 *
 * @param reason justification the operator gave; required, and journalled before the send (FR-7.3)
 */
public record OperatorSendCommand(
        Actor actor,
        String reason,
        Recipient recipient,
        ExternalMessageId externalMessageId,
        TemplateRef template,
        Target target) {

    public OperatorSendCommand {
        Guard.notNull(actor, "OperatorSendCommand.actor");
        Guard.notBlank(reason, "OperatorSendCommand.reason");
        Guard.notNull(recipient, "OperatorSendCommand.recipient");
        Guard.notNull(template, "OperatorSendCommand.template");
        Guard.notNull(target, "OperatorSendCommand.target");
    }

    /**
     * Where the message goes and under what rules — the same four things a batch header carries.
     *
     * @param trafficClass {@code null} leaves the decision to the stream
     * @param timing {@code null} means "now"
     */
    public record Target(StreamId streamId, Channel channel, TrafficClass trafficClass, Timing timing) {

        public Target {
            Guard.notNull(streamId, "Target.streamId");
            Guard.notNull(channel, "Target.channel");
        }
    }
}
