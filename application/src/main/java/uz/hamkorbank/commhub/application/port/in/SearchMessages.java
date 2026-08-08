package uz.hamkorbank.commhub.application.port.in;

import java.util.List;
import uz.hamkorbank.commhub.application.dto.MessageDigestView;
import uz.hamkorbank.commhub.application.port.in.query.MessageSearchQuery;

/**
 * The message list of the admin panel (§11.2 "Сообщения", UI-03).
 *
 * <p>Separate from {@link GetMessage}, which is the card: this one never reads content, never reads the
 * status history, and answers a page. Both journal an operator's read of customer data (SEC-08) — a list
 * of addresses is personal data as much as one card is, and the audit entry names the filter that
 * produced it.
 */
public interface SearchMessages {

    /** One page of matching messages, most recently accepted first. */
    List<MessageDigestView> search(MessageSearchQuery query);

    /** Total number of matching messages; the screen pages against it, the export stops on it. */
    long count(MessageSearchQuery query);
}
