package uz.hamkorbank.commhub.application.service;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.ProviderView;
import uz.hamkorbank.commhub.application.exception.ConfigurationConflictException;
import uz.hamkorbank.commhub.application.exception.NotFoundException;
import uz.hamkorbank.commhub.application.mapper.ConfigMapper;
import uz.hamkorbank.commhub.application.port.in.ManageProviders;
import uz.hamkorbank.commhub.application.port.in.command.DeleteProviderCommand;
import uz.hamkorbank.commhub.application.port.in.command.ProviderStateCommand;
import uz.hamkorbank.commhub.application.port.in.command.RegisterProviderCommand;
import uz.hamkorbank.commhub.application.port.in.command.UpdateProviderCommand;
import uz.hamkorbank.commhub.application.port.out.ProviderConfigRepository;
import uz.hamkorbank.commhub.application.service.support.ConfigAuditor;
import uz.hamkorbank.commhub.domain.model.ChannelConfig;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Provider profiles: registration, settings, runtime state (FR-2.1, FR-2.5, FR-2.6, FR-2.7).
 *
 * <p>Every write ends up in the {@code provider} table and reaches routing through the configuration
 * snapshot the adapters cache, so a provider disabled here stops receiving traffic within the refresh
 * window and without a restart (AD-07, NF-07).
 */
@Service
public class ProviderConfigService implements ManageProviders {

    private static final String ENTITY = "provider";

    private final ProviderConfigRepository providers;
    private final ConfigMapper mapper;
    private final ConfigAuditor auditor;

    public ProviderConfigService(ProviderConfigRepository providers, ConfigMapper mapper, ConfigAuditor auditor) {
        this.providers = Guard.notNull(providers, "providers");
        this.mapper = Guard.notNull(mapper, "mapper");
        this.auditor = Guard.notNull(auditor, "auditor");
    }

    @Override
    @Transactional
    public ProviderView register(RegisterProviderCommand command) {
        Guard.notNull(command, "command");
        providers.findProviderByCode(command.code()).ifPresent(existing -> {
            throw new ConfigurationConflictException(
                    "provider %s already exists".formatted(command.code().value()));
        });
        Provider provider = Provider.register(
                ProviderId.newId(), command.code(), command.channel(), command.adapterType(), command.settings());
        command.quotaOptional().ifPresent(provider::updateQuota);
        providers.save(provider);
        providers.saveEndpointConfig(provider.id(), command.endpointConfig());
        auditor.record(
                command.actor(), "provider.register", ENTITY, provider.code().value(), null, describe(provider));
        return mapper.toView(provider, command.endpointConfig());
    }

    @Override
    @Transactional
    public ProviderView update(UpdateProviderCommand command) {
        Guard.notNull(command, "command");
        Provider provider = require(command.providerId());
        String before = describe(provider);
        if (command.weight() != null) {
            provider.updateWeight(command.weight());
        }
        if (command.tariff() != null) {
            provider.updateTariff(command.tariff());
        }
        if (command.rateLimit() != null) {
            provider.updateRateLimit(command.rateLimit());
        }
        if (command.quota() != null) {
            provider.updateQuota(command.quota());
        }
        providers.save(provider);
        if (command.endpointConfig() != null) {
            providers.saveEndpointConfig(provider.id(), command.endpointConfig());
        }
        auditor.record(
                command.actor(), "provider.update", ENTITY, provider.code().value(), before, describe(provider));
        return view(provider);
    }

    @Override
    @Transactional
    public ProviderView changeState(ProviderStateCommand command) {
        Guard.notNull(command, "command");
        Provider provider = require(command.providerId());
        String before = describe(provider);
        switch (command.state()) {
            case ENABLED -> {
                provider.enable();
                provider.leaveMaintenance();
            }
            case DISABLED -> provider.disable();
            case MAINTENANCE -> provider.enterMaintenance();
            default -> throw new IllegalStateException("unknown provider state " + command.state());
        }
        providers.save(provider);
        auditor.record(
                command.actor(),
                "provider.state",
                ENTITY,
                provider.code().value(),
                before,
                describe(provider),
                command.reasonOptional().orElse(null));
        return view(provider);
    }

    /**
     * Removes a provider nothing references any more (FR-2.1).
     *
     * <p>Refused while a channel still lists it: silently shortening a fallback chain would leave the
     * channel looking healthy right up to the moment its primary fails (FR-2.2).
     */
    @Override
    @Transactional
    public void delete(DeleteProviderCommand command) {
        Guard.notNull(command, "command");
        Provider provider = require(command.providerId());
        List<ChannelConfig> referencing = providers.findChannels().stream()
                .filter(channel ->
                        channel.fallbackOrder().stream().map(ProviderRef::id).anyMatch(id -> id.equals(provider.id())))
                .toList();
        if (!referencing.isEmpty()) {
            throw new ConfigurationConflictException("provider %s is still in the fallback order of %s"
                    .formatted(
                            provider.code().value(),
                            referencing.stream().map(ChannelConfig::channel).toList()));
        }
        providers.deleteProvider(provider.id());
        auditor.record(
                command.actor(),
                "provider.delete",
                ENTITY,
                provider.code().value(),
                describe(provider),
                null,
                command.reason());
    }

    private Provider require(ProviderId providerId) {
        return providers.findProvider(providerId).orElseThrow(() -> NotFoundException.of(ENTITY, providerId.value()));
    }

    private ProviderView view(Provider provider) {
        return mapper.toView(provider, providers.endpointConfig(provider.id()));
    }

    /** Compact rendering for the audit trail; the profile carries no credential to leak (SEC-04). */
    private static String describe(Provider provider) {
        return "weight=%d, enabled=%s, maintenance=%s, health=%s"
                .formatted(provider.weight(), provider.isEnabled(), provider.isInMaintenance(), provider.health());
    }
}
