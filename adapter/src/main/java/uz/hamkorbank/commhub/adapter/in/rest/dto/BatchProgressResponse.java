package uz.hamkorbank.commhub.adapter.in.rest.dto;

/** Progress counters of a batch (FR-3.1). */
public record BatchProgressResponse(
        long total, long processed, long sent, long delivered, long failed, double completionPercent) {}
