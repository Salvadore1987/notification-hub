/**
 * Exception handling of the callback endpoint (project rule: one advice per concern).
 *
 * <p>Only the concern that is genuinely the callbacks' own lives here — a refused authentication,
 * which must answer without saying why. Everything else a callback can run into (an unreadable
 * payload, an unknown provider, an unexpected failure) is answered by the advices of the REST adapter,
 * which are global and already say the right thing.
 */
package uz.hamkorbank.commhub.adapter.in.callback.handlers;
