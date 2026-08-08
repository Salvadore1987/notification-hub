package uz.hamkorbank.commhub.domain.model.content;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Content of one logical notification, per channel (MP-02).
 *
 * <p>MP-02 explicitly allows one notification to carry content for several channels ("notify client N
 * over [SMS, Email, Push]"), which is also what makes a cross-channel fallback chain usable (MP-03,
 * FR-8.1). The single-content shape of the illustrative snippet in SRS §5.2 is the common case:
 * {@link #of(MessageContent)}.
 */
public record MessageContents(Map<Channel, MessageContent> byChannel) {

    public MessageContents {
        Guard.notNull(byChannel, "MessageContents.byChannel");
        Guard.isTrue(!byChannel.isEmpty(), "MessageContents must carry content for at least one channel");
        Map<Channel, MessageContent> copy = new EnumMap<>(Channel.class);
        byChannel.forEach((channel, content) -> {
            Guard.notNull(channel, "MessageContents.channel");
            Guard.notNull(content, "MessageContents.content");
            Guard.isTrue(
                    channel == content.channel(),
                    "MessageContents: content for %s is a %s payload".formatted(channel, content.channel()));
            copy.put(channel, content);
        });
        byChannel = Map.copyOf(copy);
    }

    /** Single-channel content — the common case (SRS §5.2). */
    public static MessageContents of(MessageContent content) {
        Guard.notNull(content, "content");
        return new MessageContents(Map.of(content.channel(), content));
    }

    /** Multi-channel content for a cross-channel fallback chain (MP-02, MP-03). */
    public static MessageContents of(MessageContent... contents) {
        Guard.notNull(contents, "contents");
        Map<Channel, MessageContent> byChannel = new LinkedHashMap<>();
        for (MessageContent content : contents) {
            Guard.notNull(content, "content");
            Guard.isTrue(
                    byChannel.put(content.channel(), content) == null,
                    "MessageContents: duplicate content for channel " + content.channel());
        }
        return new MessageContents(byChannel);
    }

    public Optional<MessageContent> forChannel(Channel channel) {
        Guard.notNull(channel, "channel");
        return Optional.ofNullable(byChannel.get(channel));
    }

    /** Content for the channel, or a validation error when the notification does not carry it. */
    public MessageContent requireForChannel(Channel channel) {
        return forChannel(channel)
                .orElseThrow(() -> new DomainValidationException("no message content for channel " + channel));
    }

    public Set<Channel> channels() {
        return byChannel.keySet();
    }

    public boolean supports(Channel channel) {
        return channel != null && byChannel.containsKey(channel);
    }

    public int size() {
        return byChannel.size();
    }

    /** Replaces the content of the channel it belongs to, e.g. after template rendering (FR-4.3). */
    public MessageContents with(MessageContent replacement) {
        Guard.notNull(replacement, "replacement");
        Map<Channel, MessageContent> updated = new EnumMap<>(byChannel);
        updated.put(replacement.channel(), replacement);
        return new MessageContents(updated);
    }
}
