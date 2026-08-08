package uz.hamkorbank.commhub.adapter.in.contract.dto;

import java.util.Map;

/**
 * {@code content.push} of IK-03 (PU-03).
 *
 * @param data silent key/value payload handed to the application; counts towards the 4 KB limit (PU-11)
 */
public record PushContentPayload(String title, String body, Map<String, String> data, String deepLink, String image) {}
