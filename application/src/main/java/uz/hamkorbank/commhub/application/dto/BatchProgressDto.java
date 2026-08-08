package uz.hamkorbank.commhub.application.dto;

import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Progress of a batch as shown to the source system and in the admin panel (FR-1.6, FR-3.1).
 *
 * @param completionPercent processed share of the accepted items, 0…100
 */
public record BatchProgressDto(
        long total, long processed, long sent, long delivered, long failed, double completionPercent) {

    public BatchProgressDto {
        Guard.notNegative(total, "BatchProgressDto.total");
        Guard.notNegative(processed, "BatchProgressDto.processed");
        Guard.notNegative(sent, "BatchProgressDto.sent");
        Guard.notNegative(delivered, "BatchProgressDto.delivered");
        Guard.notNegative(failed, "BatchProgressDto.failed");
    }
}
