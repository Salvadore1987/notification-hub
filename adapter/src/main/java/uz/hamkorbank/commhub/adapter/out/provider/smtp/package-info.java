/**
 * The Email channel: the Bank's corporate SMTP relay (§9.3, EM-01…EM-03).
 *
 * <p>Same shape as the SMS adapters — request building, an error table, the shared machinery of
 * {@code provider/support} — and the same contract with {@code ProviderCallExecutor}: a relay that answered
 * produces a {@code ProviderAck}, only a call without a verdict throws (PR-01). Four things are genuinely
 * different about email, and they are what this package is about.
 *
 * <p><b>The connection is the unit of cost, not the request.</b> {@link
 * uz.hamkorbank.commhub.adapter.out.provider.smtp.SmtpTransportPool} keeps connections open and its size is
 * the concurrency limit of the channel: virtual threads make it trivial to offer a relay ten thousand
 * simultaneous connections, and a relay handed ten thousand connections stops relaying (EM-01, AR-07).
 *
 * <p><b>The Hub writes the identifier.</b> An SMTP server's queue id is local to it and never comes back;
 * the {@code Message-ID} is quoted by every delivery status notification. So the Hub sets its own
 * {@code Message-ID} and carries the same value in {@code X-Comm-Message-Id} (EM-01) — that single decision
 * is what lets a bounce arriving six hours later name the row it belongs to.
 *
 * <p><b>There is no callback.</b> The answer to "did it arrive" is another email, delivered to the envelope
 * sender. {@link uz.hamkorbank.commhub.adapter.out.provider.smtp.EmailBouncePoller} reads that mailbox and
 * feeds {@code ProcessProviderStatus}, the same use case a DLR goes through (AD-06, EM-02). Which is why the
 * envelope sender ({@code return-path}) is deployment configuration and not a runtime-editable one: it has to
 * name the mailbox the poller reads, and an operator able to change one without the other would silently
 * switch every hard bounce off.
 *
 * <p><b>A rejection is usually about the address, not the relay.</b> A campaign to a list full of retired
 * mailboxes produces a steady stream of {@code 550}s. {@link
 * uz.hamkorbank.commhub.adapter.out.provider.smtp.SmtpResponseCatalog} keeps those out of the circuit
 * breaker, and — with {@link uz.hamkorbank.commhub.adapter.out.provider.smtp.BounceCatalog} — is deliberately
 * narrow about which of them suppress the address: only an explicit "no such mailbox" ({@code 5.1.x},
 * {@code 5.2.1}). A spam filter's refusal of one wording must never cost the Bank a live customer address.
 *
 * <p>DKIM (EM-03) is implemented and off. Deliverability is the mail team's ground and a corporate relay
 * normally signs what leaves it; {@link uz.hamkorbank.commhub.adapter.out.provider.smtp.DkimSigner} exists
 * for the relays that do not, and signs the serialised message rather than the model that produced it.
 *
 * <p>Attachment bytes come from {@link
 * uz.hamkorbank.commhub.adapter.out.provider.smtp.AttachmentStore} — a mounted directory, because the Bank
 * has not chosen an object store and inventing that dependency to carry a few statement PDFs would be the
 * wrong way round. The size limits of EM-01 are not enforced here but at validation
 * ({@code EmailPolicy}): a message refused by the pipeline carries a canonical reason back to the source
 * system, and one refused by a relay carries an SMTP code nobody upstream reads.
 */
package uz.hamkorbank.commhub.adapter.out.provider.smtp;
