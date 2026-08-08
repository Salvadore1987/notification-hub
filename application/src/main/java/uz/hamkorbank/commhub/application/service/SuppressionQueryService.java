package uz.hamkorbank.commhub.application.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.SuppressionCheckView;
import uz.hamkorbank.commhub.application.dto.SuppressionView;
import uz.hamkorbank.commhub.application.mapper.SuppressionMapper;
import uz.hamkorbank.commhub.application.port.in.GetSuppressions;
import uz.hamkorbank.commhub.application.port.in.query.SuppressionCheckQuery;
import uz.hamkorbank.commhub.application.port.in.query.SuppressionQuery;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.SuppressionRepository;
import uz.hamkorbank.commhub.application.service.support.RecipientAddresses;
import uz.hamkorbank.commhub.domain.model.SuppressionEntry;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Read side of the suppression list (FR-5.1).
 *
 * <p>{@link #check} answers with the same two lookups the pipeline performs, in the same order — address
 * first, then the client — so that support and sending cannot give different answers about the same
 * recipient. It reports only entries in force: an expired ban is not the reason a message failed to arrive.
 */
@Service
public class SuppressionQueryService implements GetSuppressions {

    private final SuppressionRepository suppressions;
    private final ClockPort clock;
    private final SuppressionMapper mapper;

    public SuppressionQueryService(SuppressionRepository suppressions, ClockPort clock, SuppressionMapper mapper) {
        this.suppressions = Guard.notNull(suppressions, "suppressions");
        this.clock = Guard.notNull(clock, "clock");
        this.mapper = Guard.notNull(mapper, "mapper");
    }

    @Override
    @Transactional(readOnly = true)
    public List<SuppressionView> list(SuppressionQuery query) {
        Guard.notNull(query, "query");
        return suppressions
                .findAll(query.channel(), query.reason(), query.clientId(), query.limit(), query.offset())
                .stream()
                .map(mapper::toView)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SuppressionCheckView check(SuppressionCheckQuery query) {
        Guard.notNull(query, "query");
        Instant now = clock.now();
        Optional<SuppressionEntry> blocking = query.addressOptional()
                .map(address -> RecipientAddresses.parse(query.channel(), address))
                .flatMap(hash -> suppressions.findActiveByAddress(hash, query.channel(), now))
                .or(() -> query.clientIdOptional()
                        .flatMap(clientId -> suppressions.findActiveByClient(clientId, query.channel(), now)));
        return blocking.map(entry -> SuppressionCheckView.suppressed(query.channel(), mapper.toView(entry)))
                .orElseGet(() -> SuppressionCheckView.allowed(query.channel()));
    }
}
