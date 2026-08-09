package uz.hamkorbank.commhub.adapter.out.metrics;

/**
 * Names and tag keys of everything the Hub exports to Prometheus (OBS-01).
 *
 * <p>Constants rather than literals at the call sites because these names are an operational contract:
 * the alert rules of OBS-04 and the dashboards of OBS-05 are written against them, and renaming one
 * silently blinds a dashboard. Micrometer's dotted form is used here; the Prometheus registry turns the
 * dots into underscores, so {@code commhub.messages.accepted} is scraped as
 * {@code commhub_messages_accepted_total}.
 *
 * <p>Tag cardinality is deliberate. Stream, channel, provider, traffic class, canonical status and the
 * closed enums of the rejection reasons are all bounded by configuration; nothing here is ever tagged
 * with a recipient, a message id or a provider's free-text error description.
 */
public final class MetricNames {

    public static final String PREFIX = "commhub.";

    // --- Counters of the pipeline -------------------------------------------------

    public static final String MESSAGES_ACCEPTED = PREFIX + "messages.accepted";

    public static final String MESSAGES_REJECTED = PREFIX + "messages.rejected";

    public static final String MESSAGES_DUPLICATE = PREFIX + "messages.duplicate";

    public static final String MESSAGE_STATUS = PREFIX + "message.status";

    public static final String QUOTA_BREACHED = PREFIX + "quota.breached";

    public static final String FREQUENCY_CAP_EXCEEDED = PREFIX + "frequency.cap.exceeded";

    public static final String PAN_DETECTED = PREFIX + "pan.detected";

    public static final String RECIPIENTS_SUPPRESSED = PREFIX + "recipients.suppressed";

    // --- Timers -------------------------------------------------------------------

    public static final String PROVIDER_CALLS = PREFIX + "provider.calls";

    public static final String PIPELINE_STAGE = PREFIX + "pipeline.stage";

    // --- Gauges -------------------------------------------------------------------

    public static final String PROVIDER_CIRCUIT_STATE = PREFIX + "provider.circuit.state";

    public static final String PROVIDER_CIRCUIT_FAILURE_RATE = PREFIX + "provider.circuit.failure.rate";

    public static final String OUTBOX_PENDING = PREFIX + "outbox.pending";

    public static final String OUTBOX_OLDEST_AGE = PREFIX + "outbox.oldest.age";

    public static final String DLQ_DEPTH = PREFIX + "dlq.depth";

    // --- Tag keys -----------------------------------------------------------------

    public static final String TAG_STREAM = "stream";

    public static final String TAG_TRAFFIC_CLASS = "traffic.class";

    public static final String TAG_CHANNEL = "channel";

    public static final String TAG_PROVIDER = "provider";

    public static final String TAG_STATUS = "status";

    public static final String TAG_REASON = "reason";

    public static final String TAG_RESULT = "result";

    public static final String TAG_VERDICT = "verdict";

    public static final String TAG_STAGE = "stage";

    public static final String TAG_STATE = "state";

    public static final String TAG_BLOCKED = "blocked";

    /** FR-7.4: a configuration test send is tagged, not dropped, and business panels filter it out. */
    public static final String TAG_TEST = "test";

    /** Value used where the dimension is not known yet, e.g. the channel of a message rejected before routing. */
    public static final String NONE = "none";

    // Values of the {@code stage} tag are not listed here: a stage is a statement about the pipeline of
    // §5.1, so the core owns their names ({@code application.service.support.PipelineStages}).

    private MetricNames() {}
}
