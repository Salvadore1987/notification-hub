package uz.hamkorbank.commhub.adapter.out.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the Hub authenticates itself to the broker (SEC-01).
 *
 * <p>SASL/SCRAM or mutual TLS, whichever the Bank's cluster uses; the topic ACLs behind it are the half
 * that makes "a stream sees only its own data" true on the messaging side, and they are granted to the
 * principal named here.
 *
 * <p>The passwords arrive as values from the environment of the pod, exactly like the provider
 * credentials of SEC-04 — never a literal in a ConfigMap (NF-06, ADR-0044). They are read once, at
 * startup: a Kafka client builds its JAAS configuration when it is created and has no way to be handed
 * a new one. Rotating the broker credential therefore costs a rolling restart, which is what SEC-04's
 * "without downtime" means for a horizontally scaled consumer.
 *
 * <p>{@code toString} is masked, because a record prints its components and this one holds three.
 *
 * @param protocol {@code PLAINTEXT}, {@code SSL}, {@code SASL_PLAINTEXT} or {@code SASL_SSL}; empty
 *     leaves the client at its default and is the local stack
 * @param mechanism SASL mechanism, e.g. {@code SCRAM-SHA-512}
 * @param truststoreLocation trust material for the broker's certificate; empty uses the JVM's
 * @param keystoreLocation client certificate for mTLS; empty means SASL only
 */
@ConfigurationProperties("commhub.kafka.security")
public record KafkaSecurityProperties(
        String protocol,
        String mechanism,
        String username,
        String password,
        String truststoreLocation,
        String truststorePassword,
        String keystoreLocation,
        String keystorePassword) {

    public static KafkaSecurityProperties none() {
        return new KafkaSecurityProperties(null, null, null, null, null, null, null, null);
    }

    @Override
    public String toString() {
        return "KafkaSecurityProperties[protocol=%s, mechanism=%s, username=%s, password=***, "
                        .formatted(protocol, mechanism, username)
                + "truststoreLocation=%s, truststorePassword=***, keystoreLocation=%s, keystorePassword=***]"
                        .formatted(truststoreLocation, keystoreLocation);
    }

    /** Whether anything at all has to be applied to a client configuration. */
    public boolean isConfigured() {
        return notBlank(protocol) || notBlank(mechanism) || notBlank(keystoreLocation);
    }

    public boolean usesSasl() {
        return notBlank(mechanism) && notBlank(username);
    }

    public boolean usesSsl() {
        return notBlank(truststoreLocation) || notBlank(keystoreLocation);
    }

    static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
