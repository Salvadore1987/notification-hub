package uz.hamkorbank.commhub.adapter.in.admin.dto;

import java.util.List;

/**
 * What came of a batch sent from the panel (§11.2 "Отправка", FR-1.4).
 *
 * <p>The batch id is the interesting part: it is an ordinary batch, and the operator goes on watching it
 * on the "Рассылки" screen, where it can be paused and stopped like any other.
 *
 * @param duplicates rows the dedup window had already seen — a re-uploaded file lands entirely here
 * @param failures rows the file itself could not yield, plus rows the pipeline refused
 */
public record SendBatchResponse(
        String batchId,
        long accepted,
        long duplicates,
        long rejected,
        List<ImportResultResponse.FailureDto> failures) {}
