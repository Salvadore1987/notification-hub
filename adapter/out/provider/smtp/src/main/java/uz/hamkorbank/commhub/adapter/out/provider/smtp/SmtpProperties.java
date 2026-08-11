package uz.hamkorbank.commhub.adapter.out.provider.smtp;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import uz.hamkorbank.commhub.adapter.out.provider.support.Masking;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderResilienceProperties;
import uz.hamkorbank.commhub.domain.model.RateLimit;

/**
 * Settings of the corporate SMTP integration (§9.3, EM-01, EM-03).
 *
 * <p>The email channel has no API of its own: the Bank runs a relay, and everything the Hub can decide is
 * how it connects to it, who the message says it is from, and how fast it pushes. That is what is here.
 *
 * <p>Like the SMS adapters, the deployment settings are the defaults and the runtime half comes from
 * {@code provider.endpoint_config} (AD-07): the sender, its display name and the reply-to are changed by an
 * operator without a restart. Host, port, TLS mode, timeouts and pool size do not move — a relay is
 * deployment topology, and changing it means changing where mail leaves the Bank.
 *
 * @param server where the relay is and how to reach it
 * @param sending who the message claims to be from (EM-01)
 * @param pool reused connections; a relay charges a TLS handshake per connection, not per message
 * @param dkim optional signature (EM-03)
 * @param rateLimit throughput ceiling of the relay (EM-01, FR-2.5)
 */
@ConfigurationProperties("commhub.provider.smtp")
public record SmtpProperties(
        Boolean enabled,
        String providerCode,
        Server server,
        Sending sending,
        Pool pool,
        Dkim dkim,
        RateLimit rateLimit,
        ProviderResilienceProperties resilience) {

    public static final String DEFAULT_PROVIDER_CODE = "SMTP";

    public SmtpProperties {
        enabled = enabled != null && enabled;
        providerCode = providerCode == null || providerCode.isBlank()
                ? DEFAULT_PROVIDER_CODE
                : providerCode.trim().toUpperCase(Locale.ROOT);
        server = server == null ? Server.defaults() : server;
        sending = sending == null ? Sending.defaults() : sending;
        pool = pool == null ? Pool.defaults() : pool;
        dkim = dkim == null ? Dkim.disabled() : dkim;
        rateLimit = rateLimit == null ? RateLimit.unlimited() : rateLimit;
        // Ретрай внутри попытки разрешён: SMTP-транзакция либо принята целиком (250 после DATA), либо не
        // принята вовсе, поэтому повтор после обрыва не может оставить у релея половину письма. Дубль
        // возможен только если 250 потерялся на обратном пути — там же, где он возможен у любого SMTP-клиента.
        resilience = resilience == null ? ProviderResilienceProperties.defaults() : resilience;
    }

    public static SmtpProperties defaults() {
        return new SmtpProperties(null, null, null, null, null, null, null, null);
    }

    /** How the relay is reached (EM-01). */
    public enum Security {
        /** Plain SMTP; only acceptable inside a trusted segment, and the startup log says so. */
        NONE,
        /** Plain connection upgraded with {@code STARTTLS}, required rather than opportunistic. */
        STARTTLS,
        /** TLS from the first byte (implicit, usually port 465). */
        TLS
    }

    /**
     * Address of the relay and the budgets of one exchange (EM-01, PR-01).
     *
     * @param ehloName name announced in {@code EHLO}; relays that check it refuse an unknown one
     * @param writeTimeout budget for pushing the message out — the one that matters with attachments, where
     *     the Hub is the party doing the talking
     */
    public record Server(
            String host,
            Integer port,
            Security security,
            String ehloName,
            Credentials credentials,
            Duration connectTimeout,
            Duration readTimeout,
            Duration writeTimeout) {

        public static final int DEFAULT_STARTTLS_PORT = 587;
        public static final int DEFAULT_TLS_PORT = 465;
        public static final int DEFAULT_PLAIN_PORT = 25;
        public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
        public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(15);
        public static final Duration DEFAULT_WRITE_TIMEOUT = Duration.ofSeconds(30);

        public Server {
            security = security == null ? Security.STARTTLS : security;
            port = port == null || port <= 0 ? defaultPort(security) : port;
            credentials = credentials == null ? new Credentials(null, null) : credentials;
            connectTimeout = positiveOr(connectTimeout, DEFAULT_CONNECT_TIMEOUT);
            readTimeout = positiveOr(readTimeout, DEFAULT_READ_TIMEOUT);
            writeTimeout = positiveOr(writeTimeout, DEFAULT_WRITE_TIMEOUT);
        }

        public static Server defaults() {
            return new Server(null, null, null, null, null, null, null, null);
        }

        public boolean hasHost() {
            return host != null && !host.isBlank();
        }

        /** Protocol name jakarta.mail binds the settings under: implicit TLS is a different provider. */
        public String protocol() {
            return security == Security.TLS ? "smtps" : "smtp";
        }

        private static int defaultPort(Security security) {
            return switch (security) {
                case TLS -> DEFAULT_TLS_PORT;
                case STARTTLS -> DEFAULT_STARTTLS_PORT;
                case NONE -> DEFAULT_PLAIN_PORT;
            };
        }

        private static Duration positiveOr(Duration value, Duration fallback) {
            return value == null || value.isZero() || value.isNegative() ? fallback : value;
        }
    }

    /**
     * Relay credentials, filled from the environment of the pod (SEC-04, ADR-0044).
     *
     * <p>An unauthenticated relay is normal inside a bank network — the segment is the authentication — so
     * both being absent is a supported configuration and not a misconfiguration.
     *
     * <p>{@code toString} is masked: a record prints its components, and this one is part of a properties
     * tree something may log whole.
     */
    public record Credentials(String username, String password) {

        public boolean isConfigured() {
            return username != null && !username.isBlank() && password != null && !password.isBlank();
        }

        @Override
        public String toString() {
            return "Credentials[username=%s, password=%s]"
                    .formatted(Masking.secret(username), Masking.secret(password));
        }
    }

    /**
     * Who the message says it is from (EM-01).
     *
     * @param from address in the {@code From} header, used when the message carries none of its own
     * @param fromName display name shown by mail clients
     * @param replyTo where a customer's reply goes; usually a monitored mailbox, unlike {@code from}
     * @param returnPath envelope sender the relay sends non-delivery reports to. This is the address the
     *     bounce mailbox of EM-02 reads, and it is deliberately separate from {@code from}: a DSN is a
     *     machine's answer to a machine and must not land in a mailbox people read
     */
    public record Sending(String from, String fromName, String replyTo, String returnPath) {

        public static Sending defaults() {
            return new Sending(null, null, null, null);
        }

        public Optional<String> fromOptional() {
            return blankToEmpty(from);
        }

        public Optional<String> replyToOptional() {
            return blankToEmpty(replyTo);
        }

        public Optional<String> returnPathOptional() {
            return blankToEmpty(returnPath);
        }

        public Optional<String> fromNameOptional() {
            return blankToEmpty(fromName);
        }

        /**
         * These settings with {@code provider.endpoint_config} applied on top (AD-07, §10.1).
         *
         * <p>Keys: {@code from}, {@code from-name}, {@code reply-to}. {@code return-path} is deliberately
         * not among them: it has to name the same mailbox the bounce poller of EM-02 reads, and an operator
         * who could change one without the other would silently stop every hard bounce from arriving. It is
         * a deployment setting because the mailbox it names is.
         */
        public Sending overlay(Map<String, String> endpointConfig) {
            if (endpointConfig == null || endpointConfig.isEmpty()) {
                return this;
            }
            return new Sending(
                    endpointConfig.getOrDefault("from", from),
                    endpointConfig.getOrDefault("from-name", fromName),
                    endpointConfig.getOrDefault("reply-to", replyTo),
                    returnPath);
        }

        private static Optional<String> blankToEmpty(String value) {
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
        }
    }

    /**
     * Reused SMTP connections (EM-01).
     *
     * <p>A relay charges a TCP connection and a TLS handshake for every connection and nothing for a second
     * message on one that is already open, which for a 100 000-message run is the difference between minutes
     * and hours. The pool is also the concurrency limit: {@link #maxConnections()} is how many messages the
     * Hub can have in flight to this relay at once, whatever the size of the thread pool above it.
     *
     * @param maxMessagesPerConnection messages one connection carries before it is closed and reopened;
     *     relays commonly drop a session after a few hundred, and reconnecting on our own terms is cheaper
     *     than discovering it mid-message
     * @param idleTimeout how long an unused connection is kept; past it the relay has usually closed it
     * @param acquireTimeout how long a sender waits for a free connection before the message is failed over
     */
    public record Pool(
            Integer maxConnections, Integer maxMessagesPerConnection, Duration idleTimeout, Duration acquireTimeout) {

        public static final int DEFAULT_MAX_CONNECTIONS = 8;
        public static final int DEFAULT_MAX_MESSAGES_PER_CONNECTION = 100;
        public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(1);
        public static final Duration DEFAULT_ACQUIRE_TIMEOUT = Duration.ofSeconds(10);

        public Pool {
            maxConnections = maxConnections == null || maxConnections < 1 ? DEFAULT_MAX_CONNECTIONS : maxConnections;
            maxMessagesPerConnection = maxMessagesPerConnection == null || maxMessagesPerConnection < 1
                    ? DEFAULT_MAX_MESSAGES_PER_CONNECTION
                    : maxMessagesPerConnection;
            idleTimeout = idleTimeout == null || idleTimeout.isNegative() || idleTimeout.isZero()
                    ? DEFAULT_IDLE_TIMEOUT
                    : idleTimeout;
            acquireTimeout = acquireTimeout == null || acquireTimeout.isNegative() || acquireTimeout.isZero()
                    ? DEFAULT_ACQUIRE_TIMEOUT
                    : acquireTimeout;
        }

        public static Pool defaults() {
            return new Pool(null, null, null, null);
        }
    }

    /**
     * DKIM signature of outgoing mail (EM-03).
     *
     * <p>Off by default, and that is the expected state: SPF, DKIM and DMARC are the mail team's ground, and
     * a corporate relay normally signs everything that leaves it. The Hub signs only where it is asked to —
     * when it talks to a relay that does not, or when the Bank wants the Hub's own selector on its
     * notifications.
     *
     * @param privateKey the PKCS#8 private key itself, from the environment of the pod (SEC-04); PEM or
     *     the same PEM in base64, which is what makes a multi-line key survive a variable (ADR-0044)
     * @param headers headers covered by the signature, in the order they enter the hash
     */
    public record Dkim(Boolean enabled, String domain, String selector, String privateKey, List<String> headers) {

        /**
         * Headers a signature covers by default.
         *
         * <p>{@code From} is mandatory (RFC 6376 §5.4) and the rest are what a forwarder must not be able to
         * rewrite without breaking the signature. {@code Message-ID} is included because a bounce is matched
         * by it (EM-02) — a rewritten one would silently unbind the report from its message.
         */
        public static final List<String> DEFAULT_HEADERS =
                List.of("from", "to", "subject", "date", "message-id", "mime-version", "content-type");

        public Dkim {
            enabled = enabled != null && enabled;
            headers = headers == null || headers.isEmpty()
                    ? DEFAULT_HEADERS
                    : headers.stream()
                            .map(header -> header.trim().toLowerCase(Locale.ROOT))
                            .filter(header -> !header.isEmpty())
                            .toList();
            if (enabled) {
                requireConfigured(domain, "commhub.provider.smtp.dkim.domain");
                requireConfigured(selector, "commhub.provider.smtp.dkim.selector");
                requireConfigured(privateKey, "commhub.provider.smtp.dkim.private-key");
            }
        }

        public static Dkim disabled() {
            return new Dkim(null, null, null, null, null);
        }

        @Override
        public String toString() {
            return "Dkim[enabled=%s, domain=%s, selector=%s, privateKey=%s, headers=%s]"
                    .formatted(enabled, domain, selector, Masking.secret(privateKey), headers);
        }

        private static void requireConfigured(String value, String key) {
            if (value == null || value.isBlank()) {
                // Включённая, но недонастроенная подпись — худший из исходов: письма уходят без DKIM,
                // а эксплуатация уверена, что подписаны. Пусть падает на старте (EM-03).
                throw new IllegalArgumentException(key + " is required when DKIM signing is enabled (EM-03)");
            }
        }
    }

    /** jakarta.mail settings of one session, built from {@link Server} (EM-01, PR-01). */
    public Properties sessionProperties() {
        String protocol = server.protocol();
        Properties properties = new Properties();
        properties.put("mail." + protocol + ".host", server.host() == null ? "" : server.host());
        properties.put("mail." + protocol + ".port", String.valueOf(server.port()));
        properties.put(
                "mail." + protocol + ".auth",
                String.valueOf(server.credentials().isConfigured()));
        properties.put(
                "mail." + protocol + ".connectiontimeout",
                String.valueOf(server.connectTimeout().toMillis()));
        properties.put(
                "mail." + protocol + ".timeout",
                String.valueOf(server.readTimeout().toMillis()));
        properties.put(
                "mail." + protocol + ".writetimeout",
                String.valueOf(server.writeTimeout().toMillis()));
        if (server.ehloName() != null && !server.ehloName().isBlank()) {
            properties.put("mail." + protocol + ".localhost", server.ehloName().trim());
        }
        // Конверт (MAIL FROM), а не заголовок From: именно на этот адрес релей пришлёт отчёт о
        // недоставке, и именно его читает поллер EM-02.
        sending.returnPathOptional().ifPresent(returnPath -> properties.put("mail." + protocol + ".from", returnPath));
        if (server.security() == Security.STARTTLS) {
            properties.put("mail.smtp.starttls.enable", "true");
            // required, а не opportunistic: молчаливый откат в открытый канал — это письмо клиента,
            // ушедшее по сети без шифрования (EM-01, SEC-06).
            properties.put("mail.smtp.starttls.required", "true");
        }
        properties.put("mail.mime.charset", "UTF-8");
        // Заголовки писем сворачиваем по RFC 2047/5322 сами, где нужно; кодировщик jakarta.mail
        // не должен переписывать уже готовые значения — от этого зависит подпись DKIM (EM-03).
        properties.put("mail.mime.encodefilename", "true");
        properties.put("mail.mime.decodefilename", "true");
        return properties;
    }
}
