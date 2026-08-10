/**
 * Conversion between the admin BFF contract and the application layer, in both directions (AR-06).
 *
 * <p>Two mappers rather than one, because the two directions have different inputs and different
 * failure modes: a view is already valid and only needs rendering, while a request is whatever arrived
 * over HTTP and every field of it can be wrong. The outbound one therefore never throws, and the
 * inbound one throws {@code InboundContractException} with a field pointer for anything it cannot parse.
 */
package uz.hamkorbank.commhub.adapter.in.admin.mapper;
