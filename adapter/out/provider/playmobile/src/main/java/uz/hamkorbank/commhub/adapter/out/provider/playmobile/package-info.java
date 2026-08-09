/**
 * Driven adapter: Playmobile SMS-Broker, the primary SMS provider of the MVP (§9.1, §18.1, PM-01…PM-03).
 *
 * <p>Everything Playmobile-specific is here and nowhere else — the JSON of {@code /send}, the error
 * table of §18.1, the priority words of PM-03, the delivery-report vocabulary. The application layer
 * knows only {@code SmsProviderPort} and an {@code adapterType} of {@code playmobile-http}, which is
 * what AR-04 means by "adding a provider is a new adapter only".
 *
 * <p>The delivery-report translator lives here too, next to its status table, even though it serves the
 * driving endpoint in {@code adapter/in/callback}. It is the same integration read in the other
 * direction, and splitting it across two packages would mean two places to change when Playmobile fixes
 * a status word.
 *
 * <p>The adapter retries inside a single delivery attempt, which its counterpart at SMS Gate does not.
 * The reason is the {@code message-id}: the Hub generates it and sends it with the request, so a
 * request that arrived but was not acknowledged is recognised as a duplicate the second time (§9.1).
 * Without that property a retry would be a second SMS to a customer.
 */
package uz.hamkorbank.commhub.adapter.out.provider.playmobile;
