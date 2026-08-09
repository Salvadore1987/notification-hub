package uz.hamkorbank.commhub.adapter.out.persistence.json;

import java.util.List;
import uz.hamkorbank.commhub.domain.model.ChannelPlan;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ChannelSelectionMode;

/** {@link ChannelPlan} inside the {@code message.channel_plan} column (MP-03, FR-8.1). */
public record ChannelPlanJson(String mode, List<String> channels) {

    public static ChannelPlanJson of(ChannelPlan plan) {
        return new ChannelPlanJson(
                plan.mode().name(), plan.channels().stream().map(Channel::name).toList());
    }

    public ChannelPlan toDomain() {
        return new ChannelPlan(
                ChannelSelectionMode.valueOf(mode),
                channels == null
                        ? List.of()
                        : channels.stream().map(Channel::valueOf).toList());
    }
}
