package uz.hamkorbank.commhub.domain.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ChannelSelectionMode;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Requested channels and the policy for choosing between them (MP-03, FR-8.1).
 *
 * <p>Three modes:
 *
 * <ul>
 *   <li>{@link ChannelSelectionMode#EXPLICIT} — exactly one channel named by the source system;
 *   <li>{@link ChannelSelectionMode#MODULE_CHOICE} — the Hub decides from routing policies and stream
 *       defaults; {@code channels} may narrow the candidates or be empty;
 *   <li>{@link ChannelSelectionMode#FALLBACK_CHAIN} — ordered chain, e.g. PUSH → SMS → EMAIL, the next
 *       channel is tried when the previous one does not deliver.
 * </ul>
 *
 * @param channels ordered, distinct; empty only for {@link ChannelSelectionMode#MODULE_CHOICE}
 */
public record ChannelPlan(ChannelSelectionMode mode, List<Channel> channels) {

    public ChannelPlan {
        Guard.notNull(mode, "ChannelPlan.mode");
        channels = Guard.copyOf(channels);
        Guard.isTrue(new LinkedHashSet<>(channels).size() == channels.size(), "ChannelPlan.channels must be distinct");
        // MODULE_CHOICE accepts any number of candidate channels, including none.
        if (mode == ChannelSelectionMode.EXPLICIT) {
            Guard.isTrue(channels.size() == 1, "EXPLICIT channel plan requires exactly one channel");
        } else if (mode == ChannelSelectionMode.FALLBACK_CHAIN) {
            Guard.isTrue(channels.size() >= 2, "FALLBACK_CHAIN channel plan requires at least two channels");
        }
    }

    /** The source system names the channel itself. */
    public static ChannelPlan explicitChannel(Channel channel) {
        Guard.notNull(channel, "channel");
        return new ChannelPlan(ChannelSelectionMode.EXPLICIT, List.of(channel));
    }

    /** The Hub picks any channel the recipient is reachable on. */
    public static ChannelPlan moduleChoice() {
        return new ChannelPlan(ChannelSelectionMode.MODULE_CHOICE, List.of());
    }

    /** The Hub picks one of the listed candidate channels. */
    public static ChannelPlan moduleChoice(List<Channel> candidates) {
        return new ChannelPlan(ChannelSelectionMode.MODULE_CHOICE, candidates);
    }

    /** Ordered cross-channel fallback chain (FR-8.1). */
    public static ChannelPlan fallbackChain(Channel... orderedChannels) {
        return new ChannelPlan(ChannelSelectionMode.FALLBACK_CHAIN, List.of(orderedChannels));
    }

    /** First channel to try; empty when the Hub is free to choose any channel. */
    public Optional<Channel> primaryChannel() {
        return channels.isEmpty() ? Optional.empty() : Optional.of(channels.getFirst());
    }

    /** Next channel of the chain after {@code current}, if cross-channel fallback is configured. */
    public Optional<Channel> nextAfter(Channel current) {
        if (mode != ChannelSelectionMode.FALLBACK_CHAIN) {
            return Optional.empty();
        }
        int position = channels.indexOf(current);
        if (position < 0 || position == channels.size() - 1) {
            return Optional.empty();
        }
        return Optional.of(channels.get(position + 1));
    }

    /** Whether the plan permits sending over the given channel. */
    public boolean allows(Channel channel) {
        return channels.isEmpty() || channels.contains(channel);
    }

    public boolean hasCrossChannelFallback() {
        return mode == ChannelSelectionMode.FALLBACK_CHAIN;
    }
}
