/**
 * Nested blocks of the inbound document IK-03, bound by Jackson exactly as they appear on the wire.
 *
 * <p>These records are transport, not model: they hold strings where the domain holds value objects,
 * so that a malformed MSISDN is reported as a contract violation with a field pointer instead of
 * failing somewhere inside the pipeline. The translation into commands is the job of
 * {@code InboundPayloadMapper} (AR-06).
 */
package uz.hamkorbank.commhub.adapter.in.contract.dto;
