/**
 * Input commands of the use cases: immutable records built by the driving adapters (AR-06).
 *
 * <p>Commands speak in domain value objects — {@code Recipient}, {@code MessageContents},
 * {@code ChannelPlan}, {@code Timing} — so the parsing and format validation of a transport payload
 * happens once, in the adapter, and the core receives values whose invariants already hold (FR-1.4).
 */
package uz.hamkorbank.commhub.application.port.in.command;
