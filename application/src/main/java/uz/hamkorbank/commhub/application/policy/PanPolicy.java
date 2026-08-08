package uz.hamkorbank.commhub.application.policy;

import uz.hamkorbank.commhub.domain.model.type.Channel;

/**
 * What happens when the content validator finds a card number (PCI DSS, SEC-05).
 *
 * <p>SEC-05 makes the mode configurable, and the reason is a migration one: source systems that still
 * paste a full PAN into an email or a push body have to be found before they can be fixed, and a Hub that
 * silently drops their traffic on the first day of the rollout tells nobody which system to fix. With
 * {@link #blocking()} off the finding is counted, alerted on and logged, and the message goes on.
 *
 * <p>SMS is not part of that bargain: {@link #blocksOn(Channel)} rejects a card number in an SMS whatever
 * the mode says. SEC-05 bans a PAN in SMS outright, and an SMS is the one channel where the text travels
 * through a third party in the clear.
 */
public record PanPolicy(boolean blocking) {

    /** Reject any content carrying a card number — the deployment default (SEC-05). */
    public static PanPolicy rejecting() {
        return new PanPolicy(true);
    }

    /** Count and alert, but let the message through; SMS still rejects (SEC-05). */
    public static PanPolicy alerting() {
        return new PanPolicy(false);
    }

    /** Whether a card number found in content for this channel rejects the message (SEC-05). */
    public boolean blocksOn(Channel channel) {
        return blocking || channel == Channel.SMS;
    }
}
