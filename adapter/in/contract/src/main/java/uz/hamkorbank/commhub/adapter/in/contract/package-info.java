/**
 * The inbound wire contract of the source systems (IK-03, §8.2), shared by both ingress transports.
 *
 * <p>§8.2 says it in one line — "тело = IK-03" — so the REST body and the Kafka record carry the very
 * same document. One package therefore owns the parsing: two copies of a contract drift, and a source
 * system that switches from REST to Kafka would meet a subtly different validator on the other side.
 * What the transports keep for themselves is what is genuinely theirs — status codes and
 * {@code problem+json} on the REST side, offsets and the parse-error topic on the Kafka side.
 *
 * <p>The nested blocks of IK-03 are bound by Jackson into the records of {@code dto}; the flat root is
 * assembled field by field by {@link uz.hamkorbank.commhub.adapter.in.contract.InboundMessageCodec},
 * because with its thirteen fields it is past the eight components a record may carry here — the same
 * reason the outbound §6.4 event is written by hand in {@code adapter/out/kafka}.
 *
 * <p>Validation is deliberately left to the domain value objects: {@code Msisdn} knows the
 * {@code 9989xxxxxxxx} rule of Playmobile, {@code SmsContent} knows its length limit, and a second set
 * of bean-validation annotations here would be a copy that can disagree with them. The codec adds only
 * what the value objects cannot see — an absent required field, an unknown enum constant, a malformed
 * timestamp — and reports all of it as {@code InboundContractException} with a pointer to the field.
 */
package uz.hamkorbank.commhub.adapter.in.contract;
