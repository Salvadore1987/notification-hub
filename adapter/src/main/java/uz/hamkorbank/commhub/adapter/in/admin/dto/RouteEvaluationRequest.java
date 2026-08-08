package uz.hamkorbank.commhub.adapter.in.admin.dto;

/**
 * Body of the dry run "which route would this message get" (§11.2 "Маршрутизация").
 *
 * <p>Nothing is sent and nothing is stored: the query builds a message in memory and runs the real
 * router over the real configuration. The text is here because the answer includes a cost, and a cost
 * without the wording would be a cost for a message of unknown length (§18.3).
 */
public record RouteEvaluationRequest(
        String streamId, RecipientDto recipient, String channel, String trafficClass, String priority, String text) {}
