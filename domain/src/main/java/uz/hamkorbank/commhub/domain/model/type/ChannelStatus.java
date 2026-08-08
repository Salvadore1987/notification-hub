package uz.hamkorbank.commhub.domain.model.type;

/** Operational status of a channel; switchable at runtime without a restart (FR-2.7, AD-07). */
public enum ChannelStatus {
    ACTIVE,
    MAINTENANCE,
    DISABLED;

    public boolean acceptsTraffic() {
        return this == ACTIVE;
    }
}
