package uz.hamkorbank.commhub.adapter.out.compliance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uz.hamkorbank.commhub.application.policy.FrequencyCapPolicy;
import uz.hamkorbank.commhub.application.policy.PanPolicy;

/**
 * Turns the deployment settings of the compliance filters into the policies the pipeline reads (FR-5.4,
 * SEC-05).
 *
 * <p>The policies are plain records in the application layer, so somebody has to build them from
 * configuration, and that somebody belongs on this side of the port — the pipeline must not know that its
 * numbers came from a yaml file (AR-03).
 */
@Configuration
public class CompliancePolicyConfig {

    private static final Logger LOG = LoggerFactory.getLogger(CompliancePolicyConfig.class);

    @Bean
    public FrequencyCapPolicy frequencyCapPolicy(ComplianceProperties properties) {
        ComplianceProperties.FrequencyCap cap = properties.frequencyCap();
        LOG.info(
                "Frequency cap (FR-5.4): {} messages per {} per recipient, {}",
                cap.maxMessages(),
                cap.window(),
                cap.blocking() ? "rejecting over the cap" : "counters and alerts only");
        return new FrequencyCapPolicy(cap.maxMessages(), cap.window(), cap.blocking());
    }

    @Bean
    public PanPolicy panPolicy(ComplianceProperties properties) {
        if (!properties.panBlocking()) {
            // Режим для миграции: конвейер найдёт системы-источники, которые ещё кладут PAN в текст (SEC-05).
            LOG.warn("PAN detection (SEC-05) is in alert-only mode: only SMS content will be rejected");
        }
        return new PanPolicy(properties.panBlocking());
    }
}
