package uz.hamkorbank.commhub.adapter.in.admin.support;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.adapter.in.rest.security.SecurityProperties;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Whether the admin BFF is running without an identity provider (SEC-02, UI-02).
 *
 * <p>Referenced from every {@code @PreAuthorize} in {@link AdminAuthority} as
 * {@code @adminAccess.open()}. Without it a contour with no OIDC issuer would have an admin panel that
 * refuses every request — including the developer's own — because {@code hasRole} denies an anonymous
 * caller, and the fix somebody would reach for is deleting the annotations. That is the failure mode
 * this bean exists to prevent: the role checks stay written down, and they become real the moment an
 * issuer is configured.
 *
 * <p>Which is also why it warns at startup rather than staying quiet. The rest of this system takes the
 * same position for source systems (SEC-01): a default that requires a token is a default every
 * developer switches off, so instead the instance says out loud what is missing.
 */
@Component("adminAccess")
public class AdminAccess {

    private static final Logger LOG = LoggerFactory.getLogger(AdminAccess.class);

    private final SecurityProperties properties;

    public AdminAccess(SecurityProperties properties) {
        this.properties = Guard.notNull(properties, "properties");
    }

    @PostConstruct
    void warnWhenOpen() {
        if (open()) {
            LOG.warn("Admin BFF authorisation is inactive: commhub.security.oauth2-enabled is off, so the RBAC "
                    + "checks of SEC-03 cannot be evaluated and every endpoint under /api/admin/v1 is reachable "
                    + "without a token. SEC-02 expects OIDC in the Bank's contour.");
        }
    }

    /**
     * True while no token can be validated, i.e. while roles cannot be established at all.
     *
     * <p>Keyed on OAuth2 alone and not on {@code isAuthenticationRequired()}: mTLS identifies a source
     * system by its certificate and carries no SSO groups, so an mTLS-only deployment still has nothing
     * to check a role against.
     */
    public boolean open() {
        return !properties.oauth2Enabled();
    }
}
