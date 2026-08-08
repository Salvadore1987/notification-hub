package uz.hamkorbank.commhub.application.port.in;

import uz.hamkorbank.commhub.application.dto.EventExportResult;
import uz.hamkorbank.commhub.application.port.in.command.ExportDeliveryEventsCommand;

/**
 * Feeds finished sends to the Bank's data mart (FR-6.4).
 *
 * <p>Driven by a scheduler, like the outbox relay, and for the same reason: an export that ran inside the
 * sending transaction would make the mart's availability a condition of accepting a message.
 */
public interface ExportDeliveryEvents {

    EventExportResult export(ExportDeliveryEventsCommand command);
}
