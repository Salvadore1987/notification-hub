package uz.hamkorbank.commhub.adapter.in.contract.dto;

/**
 * Header of a batch send (§8.2 {@code POST /batches}, FR-1.6).
 *
 * @param batchId identifier proposed by the source system; absent lets the Hub generate a UUIDv7
 * @param expectedTotal announced number of items, used for the progress bar while chunks arrive
 * @param test test send: delivered but excluded from business statistics (FR-7.4)
 */
public record CreateBatchPayload(
        String batchId,
        String streamId,
        String channel,
        String trafficClass,
        TimingPayload timing,
        TemplatePayload template,
        Long expectedTotal,
        Boolean test) {}
