package uz.hamkorbank.commhub.application.service;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.ChannelView;
import uz.hamkorbank.commhub.application.dto.ProviderView;
import uz.hamkorbank.commhub.application.dto.RoutingPolicyView;
import uz.hamkorbank.commhub.application.dto.StreamView;
import uz.hamkorbank.commhub.application.mapper.ConfigMapper;
import uz.hamkorbank.commhub.application.port.in.GetRoutingConfiguration;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.ProviderConfigRepository;
import uz.hamkorbank.commhub.application.port.out.StreamRepository;
import uz.hamkorbank.commhub.domain.support.Guard;

/** Read side of the routing configuration for the administration screens (§11.2, FR-2.1…FR-2.7). */
@Service
public class RoutingConfigQueryService implements GetRoutingConfiguration {

    private final ProviderConfigRepository configuration;
    private final StreamRepository streams;
    private final ClockPort clock;
    private final ConfigMapper mapper;

    public RoutingConfigQueryService(
            ProviderConfigRepository configuration, StreamRepository streams, ClockPort clock, ConfigMapper mapper) {
        this.configuration = Guard.notNull(configuration, "configuration");
        this.streams = Guard.notNull(streams, "streams");
        this.clock = Guard.notNull(clock, "clock");
        this.mapper = Guard.notNull(mapper, "mapper");
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProviderView> providers() {
        return configuration.findAllProviders().stream()
                .map(provider -> mapper.toView(provider, configuration.endpointConfig(provider.id())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChannelView> channels() {
        return configuration.findChannels().stream().map(mapper::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StreamView> streams() {
        Instant now = clock.now();
        return streams.findAll().stream()
                .map(stream -> mapper.toView(stream, now))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoutingPolicyView> policies() {
        return configuration.findPolicies().stream().map(mapper::toView).toList();
    }
}
