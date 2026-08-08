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
    private final MessageMapper mapper;

    public MessageQueryService(MessageRepository messages, MessageMapper mapper) {
        this.messages = Guard.notNull(messages, "messages");
        this.mapper = Guard.notNull(mapper, "mapper");
    }

    @Override
    @Transactional(readOnly = true)
    public MessageView get(MessageQuery query) {
        Guard.notNull(query, "query");
        return find(query).map(mapper::toView).orElseThrow(() -> NotFoundException.of(ENTITY_TYPE, query.describe()));
    }

    private Optional<Message> find(MessageQuery query) {
        return query.messageIdOptional()
                .map(messages::findById)
                .orElseGet(() -> messages.findByExternalId(query.streamId(), query.externalMessageId()));
    }
}
