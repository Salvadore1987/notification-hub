package uz.hamkorbank.commhub.adapter.in.admin.dto;

/**
 * An adapter implementation deployed on this contour (§11.2 "Каналы и провайдеры", AR-04).
 *
 * <p>Not a dictionary and not configuration: these are the channel-port beans this deployment
 * actually carries, i.e. exactly the {@code adapterType} values for which a send will find an
 * adapter. The set changes with a release, never with an edit in the panel.
 */
public record DeployedAdapterResponse(String adapterType, String channel) {}
