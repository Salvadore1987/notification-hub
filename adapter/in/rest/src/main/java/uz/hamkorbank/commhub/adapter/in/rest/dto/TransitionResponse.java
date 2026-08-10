package uz.hamkorbank.commhub.adapter.in.rest.dto;

/** One canonical transition in the life of a message (§6.3, ST-01). */
public record TransitionResponse(String status, String reason, String detail, String occurredAt) {}
