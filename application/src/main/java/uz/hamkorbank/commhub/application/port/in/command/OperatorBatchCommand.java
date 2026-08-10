package uz.hamkorbank.commhub.application.port.in.command;

import java.util.List;
import java.util.Map;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.TemplateRef;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.Recipient;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * A batch an operator sends from an uploaded recipient list (ADR-0038, §11.2, FR-1.6).
 *
 * <p>It becomes an ordinary {@code Batch}: visible on the "Рассылки" screen, pausable, stoppable and
 * counted like any other. There is no separate "panel campaign" entity, and there is not meant to be.
 *
 * @param template the published template every row is rendered from; rows carry only their variables
 * @param reason justification the operator gave; required, and journalled before the send (FR-7.3)
 */
public record OperatorBatchCommand(
        Actor actor,
        String reason,
        BatchId batchId,
        TemplateRef template,
        OperatorSendCommand.Target target,
        List<Item> items) {

    public OperatorBatchCommand {
        Guard.notNull(actor, "OperatorBatchCommand.actor");
        Guard.notBlank(reason, "OperatorBatchCommand.reason");
        Guard.notNull(template, "OperatorBatchCommand.template");
        Guard.notNull(target, "OperatorBatchCommand.target");
        items = Guard.copyOf(items);
        Guard.isTrue(!items.isEmpty(), "OperatorBatchCommand.items must not be empty");
    }

    /**
     * One row of the uploaded list.
     *
     * @param externalMessageId identifier the file carried, or one derived from the file itself so that
     *     re-uploading it inside the dedup window is a no-op rather than a second SMS (FR-1.5)
     * @param variables merge fields of this row — the whole reason a list is a list
     */
    public record Item(ExternalMessageId externalMessageId, Recipient recipient, Map<String, String> variables) {

        public Item {
            Guard.notNull(externalMessageId, "Item.externalMessageId");
            Guard.notNull(recipient, "Item.recipient");
            variables = variables == null ? Map.of() : Map.copyOf(variables);
        }
    }
}
