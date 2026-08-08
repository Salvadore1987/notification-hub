/**
 * Response bodies of the source-system API (§8.2).
 *
 * <p>Everything is a string on the way out — identifiers, instants, enum constants, money — so the
 * published contract does not change shape when a value object inside gains a component. Instants are
 * ISO-8601 UTC, exactly as the outbound status event of §6.4 writes them, so a source system parses
 * both the same way.
 */
package uz.hamkorbank.commhub.adapter.in.rest.dto;
