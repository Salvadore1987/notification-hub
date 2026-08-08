package uz.hamkorbank.commhub.application.port.in.command;

import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * A new template card in the catalogue (FR-4.1).
 *
 * <p>The channel is fixed at creation: a template renders into the content of one channel, and a text
 * written for 160 SMS characters is not an email body. A cross-channel notification is a message with a
 * fallback chain over several templates (MP-02, MP-03), not one template pretending to serve both.
 *
 * @param direction business direction of the Bank: МСБ, Чакана, Ундирув … (§18.4, FR-4.6)
 */
public record CreateTemplateCommand(Actor actor, TemplateCode code, Channel channel, String direction, String owner) {

    public CreateTemplateCommand {
        Guard.notNull(actor, "CreateTemplateCommand.actor");
        Guard.notNull(code, "CreateTemplateCommand.code");
        Guard.notNull(channel, "CreateTemplateCommand.channel");
    }
}
