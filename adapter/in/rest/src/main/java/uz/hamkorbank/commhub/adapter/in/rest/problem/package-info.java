/**
 * The error vocabulary of the source-system API: RFC 9457 documents and the codes of IR-01.
 *
 * <p>Split from {@code handlers} deliberately — the catalogue of codes is a published contract that
 * outlives the advices that render it, and the Kafka ingress reuses the same code names when it
 * reports a rejection back to the source system.
 */
package uz.hamkorbank.commhub.adapter.in.rest.problem;
