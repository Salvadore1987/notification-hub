package uz.hamkorbank.commhub.adapter.in.rest.dto;

/**
 * Route and cost of a message (§8.2, FR-6.2).
 *
 * @param segments SMS segments the text was split into; drives the cost (§18.3)
 * @param cost {@code "<amount> <currency>"}, absent while the tariff of the route is unknown
 * @param terminalAt absent while the message is still in flight
 */
public record DeliveryResponse(
        String channel,
        String provider,
        int segments,
        String cost,
        String acceptedAt,
        String terminalAt,
        String correlationId,
        boolean test) {}
