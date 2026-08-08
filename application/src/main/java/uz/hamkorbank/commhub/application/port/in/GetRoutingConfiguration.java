package uz.hamkorbank.commhub.application.port.in;

import java.util.List;
import uz.hamkorbank.commhub.application.dto.ChannelView;
import uz.hamkorbank.commhub.application.dto.ProviderView;
import uz.hamkorbank.commhub.application.dto.RoutingPolicyView;
import uz.hamkorbank.commhub.application.dto.StreamView;

/**
 * Read side of the routing configuration (FR-2.1…FR-2.7, FR-8.9, §11.2).
 *
 * <p>Separate from the {@code Manage…} use cases and read-only, like {@code GetMessage} and
 * {@code GetBatch}: the administration screens list configuration far more often than they change it.
 */
public interface GetRoutingConfiguration {

    List<ProviderView> providers();

    List<ChannelView> channels();

    List<StreamView> streams();

    List<RoutingPolicyView> policies();
}
