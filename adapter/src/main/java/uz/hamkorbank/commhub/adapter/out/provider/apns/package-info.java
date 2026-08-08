/**
 * Driven adapter: Apple Push Notification service over HTTP/2 (§9.4.2, PU-06…PU-08, PU-13).
 *
 * <p>The direct iOS path of the push channel. It is not the only one — FCM can relay to APNs on the
 * Bank's behalf (PU-05) — and the two coexist as ordinary providers of the same channel: which one an
 * iOS message takes is configuration, not code (AR-04, FR-2.2).
 *
 * <p>What is specific to Apple and therefore lives here:
 *
 * <ul>
 *   <li><b>Authentication is a rotating signature.</b> A JWT signed with the {@code .p8} key, re-signed
 *       on a schedule inside the 20-to-60-minute window Apple allows
 *       ({@link uz.hamkorbank.commhub.adapter.out.provider.apns.ApnsJwtProvider}). Its one non-obvious
 *       piece is the DER-to-JOSE conversion of the ECDSA signature — get it wrong and Apple answers
 *       {@code InvalidProviderToken} without saying why.
 *   <li><b>HTTP/2 multiplexing instead of a connection pool</b> (PU-07). The JDK client multiplexes
 *       hundreds of streams over a few connections and handles {@code GOAWAY} by draining and
 *       reconnecting; on virtual threads that is exactly the property PU-07 asks for, and hand-writing
 *       a pool would only reproduce it less well (AR-07).
 *   <li><b>Two hosts, chosen per message.</b> A token from a development build exists only on the
 *       sandbox, so a test send (FR-7.4) goes there and everything else to production (PU-13). Getting
 *       this wrong looks exactly like a dead token, which is why the choice is explicit.
 *   <li><b>The meaning is in the {@code reason}, not the status</b>
 *       ({@link uz.hamkorbank.commhub.adapter.out.provider.apns.ApnsResponseCatalog}): a 400 is both a
 *       malformed payload and a token belonging to another application, and only the second retires an
 *       address (PU-08).
 * </ul>
 *
 * <p>Certificate-based authentication is deliberately not implemented: PU-06 specifies token-based, and
 * a p12 keystore is a second credential format with an expiry date, which is a second way for the push
 * channel to stop at three in the morning.
 */
package uz.hamkorbank.commhub.adapter.out.provider.apns;
