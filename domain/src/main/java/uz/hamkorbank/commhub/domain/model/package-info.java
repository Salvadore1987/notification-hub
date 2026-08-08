/**
 * Domain model of the Notification Hub: aggregates and the value objects that make up their state
 * (SRS §5.2, §6.1).
 *
 * <p>Aggregates: {@code Message} (unit of delivery, with its status history and delivery attempts),
 * {@code Batch}, {@code Stream}, {@code ChannelConfig}, {@code Provider}, {@code RoutingPolicy},
 * {@code Template} + {@code TemplateVersion}, {@code SuppressionEntry}, {@code DlqEntry}.
 *
 * <p>Naming note: the {@code Channel} aggregate of §6.1 is called {@code ChannelConfig} here, because
 * the name {@code Channel} is taken by the channel enum used across the pipeline and in the outbound
 * status format (§6.4).
 *
 * <p>No frameworks, no persistence annotations, no clock access: instants are always passed in by the
 * application layer (AR-02).
 */
package uz.hamkorbank.commhub.domain.model;
