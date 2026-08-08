package uz.hamkorbank.commhub.adapter.in.admin.dto;

/**
 * Body of writing a template version (§11.2 "Шаблоны", FR-4.1).
 *
 * <p>No author field. The author is the authenticated actor and nothing else (FR-4.2) — a body naming
 * its own author is a four-eyes rule anybody can fill in twice.
 *
 * @param version the draft to rewrite; {@code null} starts the next one. Only a {@code DRAFT} may be
 *     rewritten: a version on review is what a reviewer is looking at, and a published one is what
 *     already-sent messages were rendered from.
 * @param html email alternative, only ever alongside {@code text} and only on an email template
 */
public record TemplateVersionRequest(String locale, Integer version, String subject, String text, String html) {}
