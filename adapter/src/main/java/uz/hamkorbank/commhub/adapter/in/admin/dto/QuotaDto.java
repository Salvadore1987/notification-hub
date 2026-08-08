package uz.hamkorbank.commhub.adapter.in.admin.dto;

/**
 * A quota, in the shape the panel both reads and writes (FR-2.6).
 *
 * <p>One record for both directions rather than a request and a response that drift apart: an operator
 * edits what they were shown, and a form whose fields are not exactly the fields of the answer is a
 * form that silently drops the ones it forgot.
 *
 * @param behavior {@code BLOCK} or {@code ALERT_ONLY} — whether an exhausted quota stops sends or only
 *     raises an alert; a null on the way in means "leave the quota alone"
 */
public record QuotaDto(Long dailyCount, Long monthlyCount, String dailyCost, String monthlyCost, String behavior) {}
