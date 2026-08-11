package uz.hamkorbank.commhub.adapter.out.kafka;

import java.util.Map;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Applies the broker credentials of SEC-01 to a producer or consumer configuration.
 *
 * <p>One place for both clients: the Hub authenticates as one principal, and a producer that talks to a
 * secured cluster while a consumer does not is a deployment that half-works and takes an outage to
 * discover. Nothing is applied when nothing is configured, which is the local stack.
 *
 * <p>The JAAS configuration is assembled here rather than pasted into a ConfigMap, so the password
 * reaches the client from the environment of the pod and never from a file the platform renders
 * (SEC-04, NF-06, ADR-0044). A bean rather than a static helper because the producer and the consumer
 * factories both take it, and one configured principal is the point.
 */
@Component
public class KafkaSecurityConfigurer {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaSecurityConfigurer.class);

    private static final String SCRAM_LOGIN_MODULE = "org.apache.kafka.common.security.scram.ScramLoginModule";

    private static final String PLAIN_LOGIN_MODULE = "org.apache.kafka.common.security.plain.PlainLoginModule";

    private final KafkaSecurityProperties properties;

    public KafkaSecurityConfigurer(KafkaSecurityProperties properties) {
        this.properties = Guard.notNull(properties, "properties");
    }

    /** Adds protocol, SASL and TLS settings to the client configuration, in place. */
    public void apply(Map<String, Object> config) {
        if (!properties.isConfigured()) {
            LOG.warn(
                    "Kafka client security is not configured; SEC-01 expects SASL/SCRAM or mTLS on the Bank's cluster");
            return;
        }
        if (KafkaSecurityProperties.notBlank(properties.protocol())) {
            config.put(
                    CommonClientConfigs.SECURITY_PROTOCOL_CONFIG,
                    properties.protocol().trim());
        }
        if (properties.usesSasl()) {
            config.put(SaslConfigs.SASL_MECHANISM, properties.mechanism().trim());
            config.put(SaslConfigs.SASL_JAAS_CONFIG, jaasConfig());
        }
        if (properties.usesSsl()) {
            applySsl(config);
        }
    }

    /**
     * The JAAS entry, refusing to be built without a password.
     *
     * <p>A blank one used to be impossible — the resolver threw on a reference that led nowhere. Now it
     * is an unset variable, and a client configured with {@code password=""} fails at the first publish
     * with a broker-side authentication error that names neither the pod nor the property.
     */
    private String jaasConfig() {
        if (!KafkaSecurityProperties.notBlank(properties.password())) {
            throw new IllegalStateException(
                    "commhub.kafka.security.password is required when SASL is configured (SEC-01)");
        }
        String module =
                properties.mechanism().toUpperCase(java.util.Locale.ROOT).startsWith("SCRAM")
                        ? SCRAM_LOGIN_MODULE
                        : PLAIN_LOGIN_MODULE;
        return "%s required username=\"%s\" password=\"%s\";"
                .formatted(module, properties.username(), properties.password());
    }

    private void applySsl(Map<String, Object> config) {
        if (KafkaSecurityProperties.notBlank(properties.truststoreLocation())) {
            config.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, properties.truststoreLocation());
            putPassword(config, SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, properties.truststorePassword());
        }
        if (KafkaSecurityProperties.notBlank(properties.keystoreLocation())) {
            config.put(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, properties.keystoreLocation());
            putPassword(config, SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, properties.keystorePassword());
            putPassword(config, SslConfigs.SSL_KEY_PASSWORD_CONFIG, properties.keystorePassword());
        }
    }

    private static void putPassword(Map<String, Object> config, String key, String password) {
        if (KafkaSecurityProperties.notBlank(password)) {
            config.put(key, password);
        }
    }
}
