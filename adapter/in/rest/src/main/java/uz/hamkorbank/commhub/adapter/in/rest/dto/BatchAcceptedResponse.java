package uz.hamkorbank.commhub.adapter.in.rest.dto;

/** Answer to {@code POST /batches} (§8.2, FR-1.6): the batch exists and its items may be uploaded. */
public record BatchAcceptedResponse(String batchId, String status, long total) {}
