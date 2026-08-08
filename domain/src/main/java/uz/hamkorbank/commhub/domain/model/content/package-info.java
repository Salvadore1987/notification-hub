/**
 * Sealed {@code MessageContent} hierarchy of the Message pattern (SRS §5.2, MP-02).
 *
 * <p>The types live in one package because a sealed hierarchy without JPMS requires its permitted
 * subclasses to be package-mates. Adding a channel means adding a specialisation here (AR-05).
 */
package uz.hamkorbank.commhub.domain.model.content;
