package uz.hamkorbank.commhub.application.port.in;

import uz.hamkorbank.commhub.application.dto.ChannelView;
import uz.hamkorbank.commhub.application.port.in.command.ChannelStateCommand;
import uz.hamkorbank.commhub.application.port.in.command.ConfigureChannelCommand;

/**
 * Administration of the channels: balancing strategy, fallback order, runtime state (FR-2.2, FR-2.3,
 * FR-2.7).
 *
 * <p>Channels are not created and deleted — they are the three of {@code Channel} (§6.4) — so the use
 * case configures and switches them instead.
 */
public interface ManageChannels {

    /** Sets strategy, provider order, quiet hours and quota of a channel (FR-2.2, FR-2.3, FR-2.6). */
    ChannelView configure(ConfigureChannelCommand command);

    /** Activates a channel, disables it or puts it into maintenance (FR-2.7). */
    ChannelView changeState(ChannelStateCommand command);
}
