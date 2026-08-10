package uz.hamkorbank.commhub.adapter.in.rest.dto;

/** Answer to {@code POST /batches/{id}/actions/{action}} (§8.2, FR-3.2). */
public record BatchActionResponse(String batchId, String status, BatchProgressResponse progress) {}
