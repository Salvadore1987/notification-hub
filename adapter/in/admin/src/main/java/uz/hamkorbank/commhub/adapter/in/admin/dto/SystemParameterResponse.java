package uz.hamkorbank.commhub.adapter.in.admin.dto;

/** One system parameter (§11.2 "Администрирование", NF-06). */
public record SystemParameterResponse(
        String key, String value, String description, String updatedAt, String updatedBy) {}
