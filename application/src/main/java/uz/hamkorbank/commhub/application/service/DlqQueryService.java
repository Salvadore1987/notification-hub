package uz.hamkorbank.commhub.application.service;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.DlqEntryView;
import uz.hamkorbank.commhub.application.mapper.DlqMapper;
import uz.hamkorbank.commhub.application.port.in.GetDlq;
import uz.hamkorbank.commhub.application.port.in.query.DlqQuery;
import uz.hamkorbank.commhub.application.port.out.DlqRepository;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * The dead-letter queue screen (§11.2 "DLQ", FR-3.3, UI-03).
 *
 * <p>Reading the queue is not audited. It holds no addresses and no content — only message ids and the
 * reason each landed there — so looking at it is not access to personal data (SEC-08); opening one of
 * its messages is, and that is journalled where the message is read.
 */
@Service
public class DlqQueryService implements GetDlq {

    private final DlqRepository entries;
    private final DlqMapper mapper;

    public DlqQueryService(DlqRepository entries, DlqMapper mapper) {
        this.entries = Guard.notNull(entries, "entries");
        this.mapper = Guard.notNull(mapper, "mapper");
    }

    @Override
    @Transactional(readOnly = true)
    public List<DlqEntryView> list(DlqQuery query) {
        Guard.notNull(query, "query");
        return entries.search(query).stream().map(mapper::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long count(DlqQuery query) {
        Guard.notNull(query, "query");
        return entries.count(query);
    }
}
