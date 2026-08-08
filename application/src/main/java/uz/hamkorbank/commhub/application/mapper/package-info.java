/**
 * MapStruct mappers — the only place where a conversion between two representations lives.
 *
 * <p>Record-to-record conversions are generated; conversions from an aggregate are written as default
 * methods, because the aggregates expose fluent accessors ({@code message.status()}) and guard their
 * optional state behind {@code Optional}, which the generator cannot read. Use cases orchestrate and
 * never map (project rule, AR-06).
 */
package uz.hamkorbank.commhub.application.mapper;
