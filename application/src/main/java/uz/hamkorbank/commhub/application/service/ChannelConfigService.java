package uz.hamkorbank.commhub.application.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.ChannelView;
import uz.hamkorbank.commhub.application.exception.ConfigurationConflictException;
import uz.hamkorbank.commhub.application.mapper.ConfigMapper;
import uz.hamkorbank.commhub.application.port.in.ManageChannels;
import uz.hamkorbank.commhub.application.port.in.command.ChannelStateCommand;
import uz.hamkorbank.commhub.application.port.in.command.ConfigureChannelCommand;
import uz.hamkorbank.commhub.application.port.out.ProviderConfigRepository;
import uz.hamkorbank.commhub.application.service.support.ConfigAuditor;
import uz.hamkorbank.commhub.domain.model.ChannelConfig;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Channel configuration: balancing strategy, primary and reserve providers, runtime state (FR-2.2,
 * FR-2.3, FR-2.6, FR-2.7).
 *
 * <p>The fallback order is given as provider codes and resolved here against the providers of the
 * channel: a code an operator typed that belongs to no provider — or to a provider of another channel —
 * is a mistake worth an error, not a silently dropped reserve.
 */
@Service
public class ChannelConfigService implements ManageChannels {

    private static final String ENTITY = "channel";

    private final ProviderConfigRepository configuration;
    private final ConfigMapper mapper;
    private final ConfigAuditor auditor;

    public ChannelConfigService(ProviderConfigRepository configuration, ConfigMapper mapper, ConfigAuditor auditor) {
        this.configuration = Guard.notNull(configuration, "configuration");
        this.mapper = Guard.notNull(mapper, "mapper");
        this.auditor = Guard.notNull(auditor, "auditor");
    }

    @Override
    @Transactional
    public ChannelView configure(ConfigureChannelCommand command) {
        Guard.notNull(command, "command");
        ChannelConfig config = configuration
                .findChannel(command.channel())
                .orElseGet(() -> ChannelConfig.of(command.channel(), command.balancingStrategy()));
        String before = describe(config);
        config.updateBalancingStrategy(command.balancingStrategy());
        config.updateFallbackOrder(resolve(command.channel(), command.fallbackOrder()));
        config.updateQuietHours(command.quietHours());
        if (command.quota() != null) {
            config.updateQuota(command.quota());
        }
        configuration.save(config);
        auditor.record(
                command.actor(), "channel.configure", ENTITY, command.channel().name(), before, describe(config));
        return mapper.toView(config);
    }

    @Override
    @Transactional
    public ChannelView changeState(ChannelStateCommand command) {
        Guard.notNull(command, "command");
        ChannelConfig config = configuration
                .findChannel(command.channel())
                .orElseThrow(() -> new ConfigurationConflictException(
                        "channel %s is not configured yet".formatted(command.channel())));
        String before = describe(config);
        switch (command.status()) {
            case ACTIVE -> config.activate();
            case MAINTENANCE -> config.enterMaintenance();
            case DISABLED -> config.disable();
            default -> throw new IllegalStateException("unknown channel status " + command.status());
        }
        configuration.save(config);
        auditor.record(
                command.actor(),
                "channel.state",
                ENTITY,
                command.channel().name(),
                before,
                describe(config),
                command.reasonOptional().orElse(null));
        return mapper.toView(config);
    }

    /** Turns the codes an operator edited into references, in the order they were given (FR-2.2). */
    private List<ProviderRef> resolve(Channel channel, List<ProviderCode> codes) {
        Map<ProviderCode, ProviderRef> known = new LinkedHashMap<>();
        configuration.findProviders(channel).stream().map(Provider::ref).forEach(ref -> known.put(ref.code(), ref));
        return codes.stream()
                .map(code -> {
                    ProviderRef ref = known.get(code);
                    if (ref == null) {
                        throw new ConfigurationConflictException(
                                "provider %s does not serve channel %s".formatted(code.value(), channel));
                    }
                    return ref;
                })
                .toList();
    }

    private static String describe(ChannelConfig config) {
        return "status=%s, strategy=%s, order=%s"
                .formatted(
                        config.status(),
                        config.balancingStrategy(),
                        config.fallbackOrder().stream()
                                .map(ref -> ref.code().value())
                                .toList());
    }
}
