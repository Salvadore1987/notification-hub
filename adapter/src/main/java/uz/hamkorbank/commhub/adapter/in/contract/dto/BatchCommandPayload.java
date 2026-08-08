package uz.hamkorbank.commhub.adapter.in.contract.dto;

/**
 * A record of {@code comm.inbound.batch-control.v1} (§8.1 IK-01): the header of a batch or a command
 * for one that already exists.
 *
 * <p>The topic carries both because both are keyed by {@code batchId}: keeping the create in the same
 * partition as the pause that may follow it is what stops a source system from pausing a batch the Hub
 * has not seen yet.
 *
 * @param action {@code CREATE}, {@code START}, {@code PAUSE}, {@code RESUME} or {@code STOP}
 * @param batch header of the batch; required for {@code CREATE} and ignored otherwise
 * @param actor who asks — the source system or an operator; recorded in the audit log (FR-7.3)
 */
public record BatchCommandPayload(
        String action, String batchId, CreateBatchPayload batch, String actor, String reason) {

    public static final String ACTION_CREATE = "CREATE";
    public static final String ACTION_START = "START";
    public static final String ACTION_PAUSE = "PAUSE";
    public static final String ACTION_RESUME = "RESUME";
    public static final String ACTION_STOP = "STOP";
}
