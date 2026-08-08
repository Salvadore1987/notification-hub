package uz.hamkorbank.commhub.bootstrap.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uz.hamkorbank.commhub.domain.service.FallbackChain;
import uz.hamkorbank.commhub.domain.service.Router;
import uz.hamkorbank.commhub.domain.service.SegmentCalculator;

/**
 * The domain services as beans (AR-02, §5).
 *
 * <p>{@code Router}, {@code FallbackChain} and {@code SegmentCalculator} are plain final classes with no
 * annotation on them, because the domain module has no compile dependency on Spring — which also means
 * the component scan cannot find them. Somebody has to say they exist, and that somebody is the
 * application assembly: this is wiring, not an adapter.
 *
 * <p>All three are stateless and take no configuration: routing reads the snapshot it is handed, and
 * segmentation is arithmetic over §18.3. One instance each is therefore not an optimisation but the
 * accurate description.
 */
@Configuration
public class DomainServiceConfig {

    @Bean
    public FallbackChain fallbackChain() {
        return new FallbackChain();
    }

    @Bean
    public Router router(FallbackChain fallbackChain) {
        return new Router(fallbackChain);
    }

    @Bean
    public SegmentCalculator segmentCalculator() {
        return new SegmentCalculator();
    }
}
