package uz.hamkorbank.commhub.adapter.out.provider.smtp;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderCallException;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * The pool of SMTP connections EM-01 asks for.
 *
 * <p>A relay charges a TCP connection, a TLS handshake and an authentication for every connection and
 * nothing at all for the second message on one that is already open. For a run of a hundred thousand
 * statements that is the whole cost of the send, so connections are kept and reused.
 *
 * <p>The pool is also the concurrency limit, deliberately. Above it sit virtual threads (AR-07), of which
 * there can be tens of thousands; a relay that is handed tens of thousands of simultaneous connections stops
 * being a relay. A sender that finds no free connection within {@code acquire-timeout} gets a retryable
 * failure and its message fails over or waits — the same answer the throttle gives, for the same reason.
 *
 * <p>A connection is dropped rather than reused when the exchange on it failed, when it has carried its
 * quota of messages, or when it has been idle long enough that the relay has probably closed it already.
 * Discovering a closed connection mid-message costs a message; closing it early costs a handshake.
 *
 * <p>The clock is {@link System#nanoTime()}: idle time must not move when NTP corrects the wall clock.
 */
public class SmtpTransportPool implements AutoCloseable {

    public static final String POOL_EXHAUSTED_CODE = "POOL_EXHAUSTED";

    private static final Logger LOG = LoggerFactory.getLogger(SmtpTransportPool.class);

    private final Session session;
    private final SmtpProperties properties;
    private final BlockingQueue<Slot> slots;

    public SmtpTransportPool(SmtpProperties properties) {
        this.properties = Guard.notNull(properties, "properties");
        this.session = Session.getInstance(properties.sessionProperties());
        int size = properties.pool().maxConnections();
        this.slots = new ArrayBlockingQueue<>(size);
        for (int index = 0; index < size; index++) {
            slots.add(new Slot());
        }
    }

    /** The session the pooled connections belong to; messages are built against the same settings. */
    public Session session() {
        return session;
    }

    /**
     * Takes a connected transport out of the pool.
     *
     * @param credentials resolved relay credentials, or {@code null} for an unauthenticated relay
     * @throws ProviderCallException when no connection is free in time, or the connection failed
     */
    public Lease borrow(SmtpCredentials credentials) {
        Slot slot = poll();
        try {
            slot.ensureConnected(session, properties, credentials);
        } catch (MessagingException | RuntimeException e) {
            slot.discard();
            slots.offer(slot);
            throw SmtpResponseCatalog.toCallException(e);
        }
        return new Lease(slot);
    }

    private Slot poll() {
        try {
            Slot slot = slots.poll(properties.pool().acquireTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (slot == null) {
                throw ProviderCallException.retryable(
                        POOL_EXHAUSTED_CODE,
                        "all %d SMTP connections are busy after %s"
                                .formatted(
                                        properties.pool().maxConnections(),
                                        properties.pool().acquireTimeout()));
            }
            return slot;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ProviderCallException.retryable(POOL_EXHAUSTED_CODE, "interrupted while waiting for a connection");
        }
    }

    @Override
    public void close() {
        List<Slot> drained = new ArrayList<>();
        slots.drainTo(drained);
        drained.forEach(Slot::discard);
    }

    /**
     * One borrowed connection, returned to the pool when it is closed.
     *
     * <p>{@link #markFailed()} rather than an inference from an exception: only the caller knows whether the
     * failure was the connection's ("the relay hung up") or the message's ("that recipient does not exist"),
     * and dropping a healthy connection after every rejected address would undo the pooling.
     */
    public final class Lease implements AutoCloseable {

        private final Slot slot;
        private boolean failed;

        private Lease(Slot slot) {
            this.slot = slot;
        }

        public Transport transport() {
            return slot.transport;
        }

        /** The connection itself is suspect; it will be closed rather than pooled. */
        public void markFailed() {
            this.failed = true;
        }

        @Override
        public void close() {
            if (failed) {
                slot.discard();
            } else {
                slot.release(properties.pool().maxMessagesPerConnection());
            }
            slots.offer(slot);
        }
    }

    /** A pool position; holds at most one connection and is owned exclusively while it is out of the queue. */
    private static final class Slot {

        private Transport transport;
        private int sent;
        private long lastUsedNanos;

        private void ensureConnected(Session session, SmtpProperties properties, SmtpCredentials credentials)
                throws MessagingException {
            if (transport != null && (!transport.isConnected() || isIdleTooLong(properties))) {
                discard();
            }
            if (transport != null) {
                return;
            }
            Transport opened = session.getTransport(properties.server().protocol());
            SmtpProperties.Server server = properties.server();
            if (credentials == null) {
                opened.connect(server.host(), server.port(), null, null);
            } else {
                opened.connect(server.host(), server.port(), credentials.username(), credentials.password());
            }
            LOG.debug("Opened an SMTP connection to {}:{}", server.host(), server.port());
            this.transport = opened;
            this.sent = 0;
            this.lastUsedNanos = System.nanoTime();
        }

        private boolean isIdleTooLong(SmtpProperties properties) {
            return System.nanoTime() - lastUsedNanos
                    >= properties.pool().idleTimeout().toNanos();
        }

        private void release(int maxMessagesPerConnection) {
            sent++;
            lastUsedNanos = System.nanoTime();
            if (sent >= maxMessagesPerConnection) {
                discard();
            }
        }

        private void discard() {
            if (transport == null) {
                return;
            }
            try {
                transport.close();
            } catch (MessagingException e) {
                LOG.debug("Closing an SMTP connection failed, dropping it anyway: {}", e.getMessage());
            } finally {
                transport = null;
                sent = 0;
            }
        }
    }
}
