package uz.hamkorbank.commhub.application.port.in.command;

import java.util.List;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.ChannelPlan;
import uz.hamkorbank.commhub.domain.model.TemplateRef;
import uz.hamkorbank.commhub.domain.model.content.MessageContents;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.Recipient;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * One chunk of batch items (FR-1.6, §8.2 {@code POST /batches/{id}/items}).
 *
 * <p>Chunks are bounded by the transport contract (up to 10 000 items per request); the use case
 * expands each item into a normal submission, so items go through the very same pipeline as single
 * messages — validation, dedup, templating, filters, routing.
 */
public record AddBatchItemsCommand(BatchId batchId, StreamId streamId, List<Item> items) {

    public AddBatchItemsCommand {
        Guard.notNull(batchId, "AddBatchItemsCommand.batchId");
        Guard.notNull(streamId, "AddBatchItemsCommand.streamId");
        items = Guard.copyOf(items);
        Guard.isTrue(!items.isEmpty(), "AddBatchItemsCommand.items must not be empty");
    }

    /**
     * One item of a chunk; everything not set here is inherited from the batch header (FR-1.6).
     *
     * @param contents per-item content; {@code null} uses the template of the item or of the batch
     * @param template per-item template override with its merge variables (FR-4.3)
     */
    public record Item(
            ExternalMessageId externalMessageId,
            Recipient recipient,
            MessageContents contents,
            TemplateRef template,
            ChannelPlan channelPlan) {

        public Item {
            Guard.notNull(externalMessageId, "Item.externalMessageId");
            Guard.notNull(recipient, "Item.recipient");
        }

        public static Item of(ExternalMessageId externalMessageId, Recipient recipient, TemplateRef template) {
            return new Item(externalMessageId, recipient, null, template, null);
        }

        public Optional<MessageContents> contentsOptional() {
            return Optional.ofNullable(contents);
        }

        public Optional<TemplateRef> templateOptional() {
            return Optional.ofNullable(template);
        }

        public Optional<ChannelPlan> channelPlanOptional() {
            return Optional.ofNullable(channelPlan);
        }
    }
}
