package uz.hamkorbank.commhub.application.port.in;

import java.util.List;
import uz.hamkorbank.commhub.application.dto.DeployedAdapterView;

/**
 * Adapter types a provider profile may name on this contour (AR-04, FR-2.1, §11.2).
 *
 * <p>Deliberately not part of {@link GetRoutingConfiguration}: everything there is stored
 * configuration an administrator edits, while this is what the deployment happens to carry. The
 * answer comes from the adapters themselves, so a new provider stays what AR-04 says it is — a new
 * bean and nothing else.
 *
 * <p>No query record and no parameters: the question has no dimensions, and the caller filters the
 * answer by the channel it is registering.
 */
public interface GetDeployedAdapters {

    List<DeployedAdapterView> adapters();
}
