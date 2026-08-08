/**
 * Driving adapters: everything that calls an input port from outside (AR-01, AR-06).
 *
 * <p>The subpackages are the transports — {@code rest} for the synchronous API of §8.2, {@code kafka}
 * for the asynchronous ingress of §8.1, {@code callback} for the provider webhooks, {@code scheduler}
 * for the jobs that are triggered by time — and {@code contract} for the inbound document both message
 * transports accept.
 *
 * <p>Only what two transports genuinely share sits directly in this package.
 */
package uz.hamkorbank.commhub.adapter.in;
