package uz.hamkorbank.commhub.application.dto;

import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * A channel adapter deployed on this contour (AR-04, FR-2.1, §11.2).
 *
 * <p>Deployment metadata rather than configuration: it is read off the container, not out of the
 * database, and it changes with a release rather than with an edit in the panel. It exists so the
 * provider form can offer the adapter types a profile may name instead of asking an operator to type
 * an opaque string.
 *
 * <p>The channel travels with the type because the two are matched together — an adapter serves one
 * channel, and {@code smtp} on an SMS profile is a provider that can never send anything.
 */
public record DeployedAdapterView(AdapterType adapterType, Channel channel) {

    public DeployedAdapterView {
        Guard.notNull(adapterType, "DeployedAdapterView.adapterType");
        Guard.notNull(channel, "DeployedAdapterView.channel");
    }
}
