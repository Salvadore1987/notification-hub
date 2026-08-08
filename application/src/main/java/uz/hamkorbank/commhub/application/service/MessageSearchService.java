package uz.hamkorbank.commhub.application.service;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.MessageDigestView;
import uz.hamkorbank.commhub.application.mapper.MessageMapper;
import uz.hamkorbank.commhub.application.port.in.SearchMessages;
import uz.hamkorbank.commhub.application.port.in.query.MessageSearchQuery;
import uz.hamkorbank.commhub.application.port.out.MessageSearchPort;
import uz.hamkorbank.commhub.application.service.support.PersonalDataAccess;
import uz.hamkorbank.commhub.domain.model.vo.AddressHash;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * The message list of the admin panel (§11.2 "Сообщения", UI-03, SEC-08).
 *
 * <p>Searching is journalled and counting is not, which is the whole of the difference between the two
 * methods. A page of a customer's messages is access to their data (SEC-08); asking how many rows a
 * filter has is the paging control asking a question about the filter, and journalling it would put a
 * second row in the log for every page turn.
 */
@Service
public class MessageSearchService implements SearchMessages {

    private final MessageSearchPort messages;
    private final PersonalDataAccess personalDataAccess;
    private final MessageMapper mapper;

    public MessageSearchService(
            MessageSearchPort messages, PersonalDataAccess personalDataAccess, MessageMapper mapper) {
        this.messages = Guard.notNull(messages, "messages");
        this.personalDataAccess = Guard.notNull(personalDataAccess, "personalDataAccess");
        this.mapper = Guard.notNull(mapper, "mapper");
    }

    /**
     * Not read-only: the access entry of SEC-08 is written in the same transaction the page is read in,
     * so "the list was shown" and "the look was journalled" cannot come apart.
     */
    @Override
    @Transactional
    public List<MessageDigestView> search(MessageSearchQuery query) {
        Guard.notNull(query, "query");
        personalDataAccess.recordMessageSearch(query.requestedBy(), addressHash(query), describe(query));
        return messages.search(query).stream().map(mapper::toDigestView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long count(MessageSearchQuery query) {
        Guard.notNull(query, "query");
        return messages.count(query);
    }

    /**
     * The searched address, reduced to the hash the journal keeps.
     *
     * <p>Everything else in a filter is an internal identifier, but a recipient is the personal datum
     * itself, and writing it into an append-only table that nothing may delete would create a second
     * store of customer addresses with no retention story (SEC-06, DB-03). The auditor's question —
     * "did somebody look this number up" — stays answerable: hash the number and compare, which is the
     * same move the suppression list already asks of them.
     */
    private static AddressHash addressHash(MessageSearchQuery query) {
        String recipient = query.filter().recipient();
        return recipient == null ? null : AddressHash.of(recipient);
    }

    /** What was asked, minus the address; the journal holds it beside the hash. */
    private static String describe(MessageSearchQuery query) {
        MessageSearchQuery.MessageFilter filter = query.filter();
        List<String> parts = new ArrayList<>();
        parts.add("period=" + query.from() + ".." + query.to());
        if (query.streamId() != null) {
            parts.add("stream=" + query.streamId());
        }
        if (query.status() != null) {
            parts.add("status=" + query.status());
        }
        if (filter.externalMessageId() != null) {
            parts.add("externalMessageId=" + filter.externalMessageId());
        }
        if (filter.correlationId() != null) {
            parts.add("correlationId=" + filter.correlationId());
        }
        if (filter.batchId() != null) {
            parts.add("batch=" + filter.batchId());
        }
        return String.join(", ", parts);
    }
}
