package uz.hamkorbank.commhub.adapter.in.contract.dto;

import java.util.List;

/**
 * {@code channels} block of IK-03 (MP-03, FR-8.1).
 *
 * @param requested channels the source system asks for; empty or absent lets the Hub choose
 * @param fallbackPolicy {@code CHAIN} to walk {@code requested} in order until one delivers;
 *     absent means the listed channels are alternatives the Hub may pick from
 */
public record ChannelsPayload(List<String> requested, String fallbackPolicy) {

    /** The only policy the MVP understands; cross-channel fallback itself arrives with Push (FR-8.1). */
    public static final String FALLBACK_CHAIN = "CHAIN";
}
