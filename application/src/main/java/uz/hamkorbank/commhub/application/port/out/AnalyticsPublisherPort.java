package uz.hamkorbank.commhub.application.port.out;

import java.util.List;

/**
 * Publishes finished sends to the Bank's data mart (FR-6.4).
 *
 * <p>Separate from {@link StatusPublisherPort} although both end up in Kafka. They are two contracts with
 * two audiences and two lifetimes: a status event is read by the source system within seconds and its
 * topic is short-lived, while the mart feed is read by a batch job and its retention is measured in
 * months. Sharing a port would tie the schema of one to the release schedule of the other.
 *
 * <p>A page at a time rather than an event at a time: the exporter has the whole page in hand and the
 * broker acknowledges the batch, which is what makes the cursor advance meaningful.
 */
public interface AnalyticsPublisherPort {

    /**
     * Publishes the page and returns only after the broker acknowledged all of it.
     *
     * @throws RuntimeException when the publication failed; the exporter leaves the cursor where it was
     */
    void publish(List<DeliveryEvent> events);
}
