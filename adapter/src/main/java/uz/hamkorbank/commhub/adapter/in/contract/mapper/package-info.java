/**
 * Translation of the inbound wire payloads into commands and value objects (AR-06).
 *
 * <p>Kept apart from the codecs on purpose: the codec knows the shape of the document — which fields
 * exist, which are required — and the mapper knows what the values mean to the domain. That is also
 * why the mapper is the only place where a {@code DomainValidationException} is turned into a contract
 * violation with a field pointer.
 */
package uz.hamkorbank.commhub.adapter.in.contract.mapper;
