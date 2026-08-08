package uz.hamkorbank.commhub.application.port.out;

import java.util.List;
import uz.hamkorbank.commhub.application.port.in.query.MessageSearchQuery;

/**
 * The list side of the message store (§11.2 "Сообщения", UI-03).
 *
 * <p>Separate from {@link MessageRepository} because it answers a different kind of question. That one
 * loads an aggregate in order to change it; this one reads rows in order to show them, never writes,
 * and is the only place in the application that may run against the read replica of DB-06.
 */
public interface MessageSearchPort {

    /** One page of matching messages, most recently accepted first. */
    List<MessageDigest> search(MessageSearchQuery query);

    /** Total number of matching messages, so the screen can page and the export can stop. */
    long count(MessageSearchQuery query);
}
