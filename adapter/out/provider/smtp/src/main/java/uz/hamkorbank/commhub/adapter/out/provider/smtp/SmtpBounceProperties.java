package uz.hamkorbank.commhub.adapter.out.provider.smtp;

import java.time.Duration;
import java.util.Locale;
import java.util.Properties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import uz.hamkorbank.commhub.adapter.out.provider.support.Masking;

/**
 * The mailbox non-delivery reports arrive in (EM-02, §9.3).
 *
 * <p>§17 leaves the mechanism open — DSN or a parsed NDR mailbox — because it is the Bank's mail team who
 * decides it. The Hub implements the one that works either way: a dedicated mailbox named by the envelope
 * sender ({@code commhub.provider.smtp.sending.return-path}), polled over IMAP. A relay that emits proper
 * DSNs and one that emits a human-readable bounce both deliver into it, and the parser handles both.
 *
 * <p>Off by default, and separately from sending: a deployment may send mail long before the mail team has
 * created the bounce mailbox, and a poller failing every minute against a mailbox that does not exist is a
 * worse way to learn that than a configuration flag.
 *
 * @param folder mailbox folder to read; {@code INBOX} unless the mail team files reports elsewhere
 * @param batchSize reports read per pass, so one pass over a mailbox that has been ignored for a month
 *     cannot hold the scheduler for an hour
 * @param deleteProcessed whether a processed report is deleted or only marked as seen. Marking is the
 *     default: a report that the Hub could not attribute to a message is evidence, and evidence a poller
 *     silently deleted cannot be looked at afterwards
 */
@ConfigurationProperties("commhub.provider.smtp.bounce")
public record SmtpBounceProperties(
        Boolean enabled,
        String host,
        Integer port,
        Boolean ssl,
        Credentials credentials,
        String folder,
        Integer batchSize,
        Settings settings) {

    public static final int DEFAULT_IMAP_PORT = 143;
    public static final int DEFAULT_IMAPS_PORT = 993;
    public static final String DEFAULT_FOLDER = "INBOX";
    public static final int DEFAULT_BATCH_SIZE = 200;

    public SmtpBounceProperties {
        enabled = enabled != null && enabled;
        ssl = ssl == null || ssl;
        port = port == null || port <= 0 ? (ssl ? DEFAULT_IMAPS_PORT : DEFAULT_IMAP_PORT) : port;
        credentials = credentials == null ? new Credentials(null, null) : credentials;
        folder = folder == null || folder.isBlank() ? DEFAULT_FOLDER : folder.trim();
        batchSize = batchSize == null || batchSize < 1 ? DEFAULT_BATCH_SIZE : batchSize;
        settings = settings == null ? Settings.defaults() : settings;
        if (enabled && (host == null || host.isBlank())) {
            throw new IllegalArgumentException(
                    "commhub.provider.smtp.bounce.host is required when bounce processing is enabled (EM-02)");
        }
    }

    public static SmtpBounceProperties disabled() {
        return new SmtpBounceProperties(null, null, null, null, null, null, null, null);
    }

    /** Mailbox credentials, filled from the environment of the pod (SEC-04, ADR-0044). */
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
     * How the poller behaves.
     *
     * @param interval delay between passes; a bounce is not urgent — the address is already dead, and the
     *     next message to it is the thing being prevented
     * @param connectTimeout budget for reaching the mail server
     * @param readTimeout budget for one command against it
     */
    public record Settings(Duration interval, Duration connectTimeout, Duration readTimeout, Boolean deleteProcessed) {

        public static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(5);
        public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
        public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);

        public Settings {
            interval = positiveOr(interval, DEFAULT_INTERVAL);
            connectTimeout = positiveOr(connectTimeout, DEFAULT_CONNECT_TIMEOUT);
            readTimeout = positiveOr(readTimeout, DEFAULT_READ_TIMEOUT);
            deleteProcessed = deleteProcessed != null && deleteProcessed;
        }

        public static Settings defaults() {
            return new Settings(null, null, null, null);
        }

        private static Duration positiveOr(Duration value, Duration fallback) {
            return value == null || value.isZero() || value.isNegative() ? fallback : value;
        }
    }

    /** Protocol name jakarta.mail binds the settings under. */
    public String protocol() {
        return ssl ? "imaps" : "imap";
    }

    /** jakarta.mail settings of the polling session. */
    public Properties sessionProperties() {
        String protocol = protocol();
        Properties properties = new Properties();
        properties.put("mail.store.protocol", protocol);
        properties.put("mail." + protocol + ".host", host == null ? "" : host);
        properties.put("mail." + protocol + ".port", String.valueOf(port));
        properties.put(
                "mail." + protocol + ".connectiontimeout",
                String.valueOf(settings.connectTimeout().toMillis()));
        properties.put(
                "mail." + protocol + ".timeout",
                String.valueOf(settings.readTimeout().toMillis()));
        if (!ssl) {
            // Не «включить, если умеет»: почтовый ящик отчётов о доставке содержит адреса клиентов,
            // и открытая сессия к нему — это те же ПДн в сети (SEC-06).
            properties.put("mail." + protocol + ".starttls.enable", "true");
        }
        properties.put("mail.mime.address.strict", "false");
        return properties;
    }

    /** Name used in log lines; the mailbox itself is never named with its credentials (PR-03). */
    public String describe() {
        return "%s://%s:%d/%s".formatted(protocol().toLowerCase(Locale.ROOT), host, port, folder);
    }
}
