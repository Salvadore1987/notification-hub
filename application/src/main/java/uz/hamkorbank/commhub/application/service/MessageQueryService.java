package uz.hamkorbank.commhub.application.service;

import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.MessageView;
import uz.hamkorbank.commhub.application.exception.NotFoundException;
import uz.hamkorbank.commhub.application.mapper.MessageMapper;
import uz.hamkorbank.commhub.application.port.in.GetMessage;
import uz.hamkorbank.commhub.application.port.in.query.MessageQuery;
import uz.hamkorbank.commhub.application.port.out.MessageRepository;
import uz.hamkorbank.commhub.application.service.support.PersonalDataAccess;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Answers "what happened to my message" (§8.2 {@code GET /messages}).
 *
 * <p>The two lookup forms hit two different indexes — the primary key and the unique
 * {@code (stream_id, external_message_id)} — so the query record decides which one is used and the
 * service does not guess.
 */
@Service
public class MessageQueryService implements GetMessage {

    private static final String ENTITY_TYPE = "message";

    private final MessageRepository messages;
    private final PersonalDataAccess personalDataAccess;
    private final MessageMapper mapper;

    public MessageQueryService(
            MessageRepository messages, PersonalDataAccess personalDataAccess, MessageMapper mapper) {
        this.messages = Guard.notNull(messages, "messages");
        this.personalDataAccess = Guard.notNull(personalDataAccess, "personalDataAccess");
        this.mapper = Guard.notNull(mapper, "mapper");
    }

    /**
     * The transaction is not read-only any more: an operator's lookup writes the access entry of SEC-08
     * in the same transaction it reads in, so "the message was shown" and "the look was journalled" cannot
     * come apart. A source system's poll writes nothing and pays nothing for it.
     */
    @Override
    @Transactional
    public MessageView get(MessageQuery query) {
        Guard.notNull(query, "query");
        Message message = find(query).orElseThrow(() -> NotFoundException.of(ENTITY_TYPE, query.describe()));
        personalDataAccess.recordMessageView(query.requestedBy(), message.id());
        return mapper.toView(message);
    }

    private Optional<Message> find(MessageQuery query) {
        return query.messageIdOptional()
                .map(messages::findById)
                .orElseGet(() -> messages.findByExternalId(query.streamId(), query.externalMessageId()));
    }
}
