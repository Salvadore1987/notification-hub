package uz.hamkorbank.commhub.adapter.in.contract.dto;

import java.util.List;

/**
 * One uploaded chunk of batch items (§8.2 {@code POST /batches/{id}/items}).
 *
 * <p>Wrapped in an object rather than sent as a bare array so the contract can grow a chunk sequence
 * number or a checksum without breaking the callers that already use it.
 */
public record BatchItemsPayload(List<BatchItemPayload> items) {}
