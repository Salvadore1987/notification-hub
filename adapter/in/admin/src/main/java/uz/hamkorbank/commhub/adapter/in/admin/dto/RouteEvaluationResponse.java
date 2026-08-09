package uz.hamkorbank.commhub.adapter.in.admin.dto;

import java.util.List;

/**
 * Answer of the dry run "which route would message X get" (§11.2 "Маршрутизация").
 *
 * <p>Produced by running the real router against the real configuration, so what this shows is what the
 * next message would actually do — including its cost, which is the figure an operator is checking when
 * they change a fallback order.
 *
 * @param rejection why no route was found; set exactly when {@code routed} is false
 */
public record RouteEvaluationResponse(
        boolean routed,
        String channel,
        String provider,
        List<String> fallbackProviders,
        String strategy,
        int segments,
        String estimatedCost,
        RejectionDto rejection) {

    public record RejectionDto(String reason, String detail) {}
}
