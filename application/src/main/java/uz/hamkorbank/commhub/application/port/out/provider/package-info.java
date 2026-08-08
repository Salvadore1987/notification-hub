/**
 * Channel output ports and their submission/acknowledgement contracts (AR-04, MP-05).
 *
 * <p>One port per channel — {@code SmsProviderPort}, {@code EmailProviderPort},
 * {@code PushProviderPort}. Every provider is a separate adapter implementing the port of its
 * channel, so adding a provider never changes {@code domain/} or {@code application/} (AR-04); adding
 * a channel adds one more port here (AR-05).
 */
package uz.hamkorbank.commhub.application.port.out.provider;
