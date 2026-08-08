package uz.hamkorbank.commhub.application.service.support;

/**
 * Names of the pipeline stages whose duration is measured (OBS-01, TC-01, NF-01).
 *
 * <p>They live in the core rather than next to the metric names in the adapter, because what a stage is
 * is a statement about §5.1 and not about Micrometer. The adapter only turns them into a tag.
 *
 * <p>Deliberately few. Every stage measured is a series per traffic class, and the two that answer an
 * SLA question are the ones kept: how long accepting a submission takes, and how long a message waits
 * between being accepted and reaching a provider. The provider leg itself is already timed separately
 * ({@code MetricsPort.providerCall}), so a third timer around it would measure the same seconds twice.
 */
public final class PipelineStages {

    /** Everything §5.1 does before the message is queued: dedup, template, validation, routing, filters. */
    public static final String ACCEPT = "accept";

    /**
     * Accept → handed to a provider, the end-to-end figure the OTP SLA is stated in: p99 ≤ 5 s (TC-01).
     *
     * <p>Recorded once per message, on the attempt that the provider accepted — retries and failovers are
     * part of the number, which is the point: what the customer waits for is the first successful send,
     * not the first try.
     */
    public static final String ACCEPT_TO_PROVIDER = "accept-to-provider";

    private PipelineStages() {}
}
