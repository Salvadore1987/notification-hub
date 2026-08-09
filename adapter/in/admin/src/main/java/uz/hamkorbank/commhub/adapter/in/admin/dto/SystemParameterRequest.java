package uz.hamkorbank.commhub.adapter.in.admin.dto;

/**
 * Body of writing a system parameter (§11.2 "Администрирование", NF-06).
 *
 * <p>The key is in the path, so it is not here: a body that could disagree with the path is a body that
 * eventually does.
 */
public record SystemParameterRequest(String value, String description) {}
