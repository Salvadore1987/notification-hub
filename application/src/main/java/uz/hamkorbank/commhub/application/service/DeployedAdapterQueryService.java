package uz.hamkorbank.commhub.application.service;

import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import uz.hamkorbank.commhub.application.dto.DeployedAdapterView;
import uz.hamkorbank.commhub.application.mapper.ConfigMapper;
import uz.hamkorbank.commhub.application.port.in.GetDeployedAdapters;
import uz.hamkorbank.commhub.application.service.support.ProviderGateway;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Adapter types the provider form may offer on this contour (AR-04, FR-2.1, §11.2).
 *
 * <p>Asks {@link ProviderGateway} rather than the container directly, because the gateway is the one
 * place that knows how a {@code ProviderRef} finds its adapter — an answer assembled anywhere else
 * would be a second opinion on the same question, and the two would diverge the first time push
 * resolution changes.
 *
 * <p><b>No transaction, unlike every sibling query service.</b> Nothing here is stored: the answer is
 * the set of beans this deployment carries, so opening a database connection for it would buy
 * nothing.
 */
@Service
public class DeployedAdapterQueryService implements GetDeployedAdapters {

    /** Sorted for the operator, not for the machine: bean definition order is nobody's contract. */
    private static final Comparator<DeployedAdapterView> BY_CHANNEL_THEN_TYPE = Comparator.comparing(
                    (DeployedAdapterView view) -> view.channel().name())
            .thenComparing(view -> view.adapterType().value());

    private final ProviderGateway gateway;
    private final ConfigMapper mapper;

    public DeployedAdapterQueryService(ProviderGateway gateway, ConfigMapper mapper) {
        this.gateway = Guard.notNull(gateway, "gateway");
        this.mapper = Guard.notNull(mapper, "mapper");
    }

    @Override
    public List<DeployedAdapterView> adapters() {
        return gateway.deployedAdapters().stream()
                .map(mapper::toView)
                .distinct()
                .sorted(BY_CHANNEL_THEN_TYPE)
                .toList();
    }
}
