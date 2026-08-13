package uz.hamkorbank.commhub.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static uz.hamkorbank.commhub.domain.DomainFixtures.NOW;
import static uz.hamkorbank.commhub.domain.DomainFixtures.envelope;
import static uz.hamkorbank.commhub.domain.DomainFixtures.msisdn;
import static uz.hamkorbank.commhub.domain.DomainFixtures.smsMessage;
import static uz.hamkorbank.commhub.domain.DomainFixtures.uzs;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;
import uz.hamkorbank.commhub.domain.exception.InvalidStatusTransitionException;
import uz.hamkorbank.commhub.domain.model.content.MessageContents;
import uz.hamkorbank.commhub.domain.model.content.PushContent;
import uz.hamkorbank.commhub.domain.model.content.SmsContent;
import uz.hamkorbank.commhub.domain.model.type.ActorType;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.PushPlatform;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.model.vo.PushToken;
import uz.hamkorbank.commhub.domain.model.vo.Recipient;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;
import uz.hamkorbank.commhub.domain.model.vo.TemplateVersionId;

/** Message aggregate: lifecycle, status history and delivery attempts (§5.2, §6.3, ST-01…ST-03). */
class MessageTest {

    private static final ProviderRef PLAYMOBILE = new ProviderRef(
            ProviderId.newId(), ProviderCode.of("PLAYMOBILE"), Channel.SMS, AdapterType.of("playmobile-http"));
    private static final ProviderRef SMSGATE = new ProviderRef(
            ProviderId.newId(), ProviderCode.of("SMSGATE"), Channel.SMS, AdapterType.of("smsgate-http"));

    @Test
    @DisplayName("an accepted message starts in ACCEPTED with a history entry from its source system")
    void acceptedMessageStartsWithHistory() {
        // Act
        Message message = smsMessage();

        // Assert
        assertThat(message.status()).isEqualTo(MessageStatus.ACCEPTED);
        assertThat(message.statusHistory()).hasSize(1);
        assertThat(message.statusHistory().getFirst().actor().type()).isEqualTo(ActorType.SOURCE_SYSTEM);
        assertThat(message.statusHistory().getFirst().occurredAt()).isEqualTo(NOW);
        assertThat(message.acceptedAt()).isEqualTo(NOW);
        assertThat(message.selectedChannel()).isEmpty();
        assertThat(message.selectedProvider()).isEmpty();
        assertThat(message.terminalAt()).isEmpty();
        assertThat(message.isTest()).isFalse();
        assertThat(message.id()).isEqualTo(message.envelope().id());
    }

    @Test
    @DisplayName("the happy path walks ACCEPTED → … → DELIVERED and records every step (ST-01)")
    void happyPathIsRecorded() {
        // Arrange
        Message message = smsMessage();
        Actor system = Actor.system();

        // Act
        message.markValidated(system, NOW);
        message.markRouted(Channel.SMS, PLAYMOBILE, system, NOW);
        message.markQueued(system, NOW);
        message.markSending(system, NOW);
        message.markSentToProvider("ACCEPTD", system, NOW);
        message.markDelivered("DLVRD", Actor.provider("PLAYMOBILE"), NOW.plusSeconds(3));

        // Assert
        assertThat(message.status()).isEqualTo(MessageStatus.DELIVERED);
        assertThat(message.statusHistory()).hasSize(7);
        assertThat(message.statusHistory().getLast().details()).isEqualTo("DLVRD");
        assertThat(message.statusHistory().getLast().providerCodeOptional()).contains(ProviderCode.of("PLAYMOBILE"));
        assertThat(message.terminalAt()).contains(NOW.plusSeconds(3));
        assertThat(message.selectedChannel()).contains(Channel.SMS);
        assertThat(message.selectedContent()).isPresent();
    }

    @Test
    @DisplayName("ST-02: a transition outside the table is refused")
    void refusesIllegalTransition() {
        // Arrange
        Message message = smsMessage();

        // Act + Assert
        assertThatExceptionOfType(InvalidStatusTransitionException.class)
                .isThrownBy(() -> message.markDelivered("DLVRD", Actor.system(), NOW))
                .withMessageContaining("ACCEPTED -> DELIVERED");
        assertThat(message.status()).isEqualTo(MessageStatus.ACCEPTED);
    }

    @Test
    @DisplayName("FR-5.1: a rejection keeps its canonical reason")
    void rejectionKeepsItsReason() {
        // Arrange
        Message message = smsMessage();

        // Act
        message.reject(RejectionReason.SUPPRESSED, "recipient opted out", Actor.system(), NOW);

        // Assert
        assertThat(message.status()).isEqualTo(MessageStatus.REJECTED);
        assertThat(message.statusReason()).contains(RejectionReason.SUPPRESSED);
        assertThat(message.terminalAt()).contains(NOW);
    }

    @Test
    @DisplayName("FR-1.5: a duplicate points at the original message")
    void duplicatePointsAtTheOriginal() {
        // Arrange
        Message message = smsMessage();
        MessageId original = MessageId.newId();

        // Act
        message.markDuplicateOf(original, Actor.system(), NOW);

        // Assert
        assertThat(message.status()).isEqualTo(MessageStatus.DUPLICATE);
        assertThat(message.statusReason()).contains(RejectionReason.DUPLICATE_SUBMISSION);
        assertThat(message.duplicateOf()).contains(original);
    }

    @Test
    @DisplayName("FR-3.4: a message whose TTL elapsed is expired while in flight")
    void ttlDrivesExpiry() {
        // Arrange
        Message message = Message.accept(
                envelope("otp-1", TrafficClass.CRITICAL_OTP),
                Recipient.ofMsisdn(msisdn()),
                ChannelPlan.explicitChannel(Channel.SMS),
                MessageContents.of(SmsContent.of("1234")),
                null,
                Timing.withTtl(Duration.ofMinutes(2)),
                NOW);

        // Act + Assert
        assertThat(message.isExpiredAt(NOW.plusSeconds(60))).isFalse();
        assertThat(message.isExpiredAt(NOW.plusSeconds(120))).isTrue();

        message.expire(Actor.system(), NOW.plusSeconds(120));
        assertThat(message.status()).isEqualTo(MessageStatus.EXPIRED);
        assertThat(message.statusReason()).contains(RejectionReason.TTL_EXPIRED);
        assertThat(message.isExpiredAt(NOW.plusSeconds(300))).isFalse();
    }

    @Test
    @DisplayName("PU-12: a push that reached the platform is finished, and the TTL never touches it again")
    void pushIsTerminalAtSentToProvider() {
        // Arrange — push с TTL: платформы о доставке не сообщают, ждать нечего
        ProviderRef fcm = new ProviderRef(
                ProviderId.newId(), ProviderCode.of("FCM"), Channel.PUSH, AdapterType.of("fcm-http-v1"));
        Message message = Message.accept(
                envelope("push-1", TrafficClass.NOTIFICATION),
                Recipient.ofPushTokens(List.of(PushToken.of("device-token-1", PushPlatform.ANDROID))),
                ChannelPlan.explicitChannel(Channel.PUSH),
                MessageContents.of(PushContent.of("Заголовок", "Текст")),
                null,
                Timing.withTtl(Duration.ofMinutes(2)),
                NOW);
        message.markValidated(Actor.system(), NOW);
        message.markRouted(Channel.PUSH, fcm, Actor.system(), NOW);
        message.markQueued(Actor.system(), NOW);
        message.markSending(Actor.system(), NOW);

        // Act
        message.markSentToProvider("SENT", Actor.provider("FCM"), NOW.plusSeconds(1));

        // Assert — иначе свип TTL пометил бы каждый доставленный push как EXPIRED и сообщил бы
        // об этом системе-источнику (PU-12, FR-3.4)
        assertThat(message.isTerminalForChannel()).isTrue();
        assertThat(message.isExpiredAt(NOW.plusSeconds(600))).isFalse();
        assertThat(message.terminalAt()).contains(NOW.plusSeconds(1));
    }

    @Test
    @DisplayName("ST-03: an SMS at SENT_TO_PROVIDER is still in flight — the provider has yet to report")
    void smsIsNotTerminalAtSentToProvider() {
        // Arrange
        Message message = routedMessage();
        message.markSending(Actor.system(), NOW);

        // Act
        message.markSentToProvider("ACCEPTD", Actor.provider("PLAYMOBILE"), NOW.plusSeconds(1));

        // Assert
        assertThat(message.isTerminalForChannel()).isFalse();
        assertThat(message.terminalAt()).isEmpty();
    }

    @Test
    @DisplayName("FR-3.3, ST-02: a DLQ retry reopens a FAILED message")
    void dlqRetryReopensAFailedMessage() {
        // Arrange
        Message message = routedMessage();
        message.markSending(Actor.system(), NOW);
        message.markRetrying("timeout", Actor.system(), NOW);
        message.markFailed("all attempts exhausted", Actor.system(), NOW);

        // Act
        message.requeueFromDlq(Actor.operator("operator-1"), NOW.plusSeconds(600));

        // Assert
        assertThat(message.status()).isEqualTo(MessageStatus.QUEUED);
        assertThat(message.terminalAt()).isEmpty();
        assertThat(message.statusHistory().getLast().actor().type()).isEqualTo(ActorType.OPERATOR);
    }

    @Test
    @DisplayName("FR-3.2: a stop cancels a message that has not been sent yet")
    void cancellationIsRecorded() {
        // Arrange
        Message message = routedMessage();

        // Act
        message.cancel(RejectionReason.SEND_STOPPED, Actor.operator("operator-1"), NOW);

        // Assert
        assertThat(message.status()).isEqualTo(MessageStatus.CANCELLED);
        assertThat(message.statusReason()).contains(RejectionReason.SEND_STOPPED);
    }

    @Test
    @DisplayName("§18.2: a provider non-delivery ends in UNDELIVERED")
    void providerNonDeliveryIsTerminal() {
        // Arrange
        Message message = routedMessage();
        message.markSending(Actor.system(), NOW);
        message.markSentToProvider("Sent", Actor.system(), NOW);

        // Act
        message.markUndelivered("Rejected", Actor.provider("SMSGATE"), NOW.plusSeconds(10));

        // Assert
        assertThat(message.status()).isEqualTo(MessageStatus.UNDELIVERED);
        assertThat(message.statusReason()).contains(RejectionReason.PROVIDER_REJECTED);
    }

    @Test
    @DisplayName("FR-4.3: rendered content replaces the original before routing")
    void renderedContentReplacesTheOriginal() {
        // Arrange
        Message message = smsMessage();

        // Act
        message.applyRenderedContent(SmsContent.of("Hello IVAN", "HAMKORBANK"));

        // Assert
        SmsContent content = (SmsContent) message.contents().requireForChannel(Channel.SMS);
        assertThat(content.text()).isEqualTo("Hello IVAN");
    }

    @Test
    @DisplayName("content can no longer be rendered once the message is routed")
    void renderingIsClosedAfterRouting() {
        // Arrange
        Message message = routedMessage();

        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> message.applyRenderedContent(SmsContent.of("late")))
                .withMessageContaining("can no longer be rendered");
    }

    @Test
    @DisplayName("rendered content for a channel the message does not carry is refused")
    void renderingForeignChannelIsRefused() {
        // Arrange
        Message message = smsMessage();

        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> message.applyRenderedContent(PushContent.of("T", "B")))
                .withMessageContaining("no content for channel PUSH");
    }

    @Test
    @DisplayName("§10.1: the version the content was rendered from is recorded next to the template reference")
    void templateVersionIsRecorded() {
        // Arrange
        Message message = smsMessage();
        TemplateVersionId version = TemplateVersionId.newId();

        // Act
        message.applyTemplateVersion(version);

        // Assert
        assertThat(message.templateVersionId()).contains(version);
        assertThat(smsMessage().templateVersionId()).isEmpty();
    }

    @Test
    @DisplayName("the template version can no longer be recorded once the message is routed")
    void templateVersionIsClosedAfterRouting() {
        // Arrange
        Message message = routedMessage();

        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> message.applyTemplateVersion(TemplateVersionId.newId()))
                .withMessageContaining("can no longer be recorded");
    }

    @Test
    @DisplayName("MP-06: segments and cost are stored on the message")
    void segmentsAndCostAreStored() {
        // Arrange
        Message message = smsMessage();

        // Act
        message.applySegments(2);
        message.applyCost(uzs("49"));
        message.markAsTest();

        // Assert
        assertThat(message.segments()).isEqualTo(2);
        assertThat(message.cost()).contains(uzs("49"));
        assertThat(message.isTest()).isTrue();
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> message.applySegments(0));
    }

    @Test
    @DisplayName("attempts are numbered consecutively against the selected provider")
    void attemptsAreNumbered() {
        // Arrange
        Message message = routedMessage();

        // Act
        DeliveryAttempt first = message.startAttempt(ProviderMessageId.of("HB0000000001"), NOW);
        first.timeout(NOW.plusSeconds(5));
        DeliveryAttempt second = message.startAttempt(ProviderMessageId.of("HB0000000002"), NOW.plusSeconds(6));

        // Assert
        assertThat(first.attemptNumber()).isEqualTo(1);
        assertThat(second.attemptNumber()).isEqualTo(2);
        assertThat(message.attempts()).containsExactly(first, second);
        assertThat(message.latestAttempt()).contains(second);
        assertThat(message.nextAttemptNumber()).isEqualTo(3);
    }

    @Test
    @DisplayName("an attempt of another message is refused")
    void refusesForeignAttempt() {
        // Arrange
        Message message = routedMessage();
        Message other = routedMessage();
        DeliveryAttempt foreign = other.startAttempt(null, NOW);

        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> message.recordAttempt(foreign))
                .withMessageContaining("belongs to another message");
    }

    @Test
    @DisplayName("an attempt cannot be opened before the message is routed")
    void attemptRequiresARoute() {
        // Arrange
        Message message = smsMessage();

        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> message.startAttempt(null, NOW))
                .withMessageContaining("not routed yet");
    }

    @Test
    @DisplayName("FR-6.3: failover re-assigns the route without touching the status")
    void failoverReassignsTheRoute() {
        // Arrange
        Message message = routedMessage();
        message.markSending(Actor.system(), NOW);
        message.markRetrying("provider 5xx", Actor.system(), NOW);

        // Act
        message.assignRoute(Channel.SMS, SMSGATE);

        // Assert
        assertThat(message.selectedProvider()).contains(SMSGATE);
        assertThat(message.status()).isEqualTo(MessageStatus.RETRYING);
    }

    @Test
    @DisplayName("a route outside the plan, for a foreign channel or on a terminal message is refused")
    void routeIsValidated() {
        // Arrange
        Message message = smsMessage();
        ProviderRef pushProvider = new ProviderRef(
                ProviderId.newId(), ProviderCode.of("FCM"), Channel.PUSH, AdapterType.of("fcm-http-v1"));

        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> message.assignRoute(Channel.PUSH, pushProvider))
                .withMessageContaining("not allowed by the channel plan");
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> message.assignRoute(Channel.SMS, pushProvider))
                .withMessageContaining("does not serve channel");

        message.reject(RejectionReason.VALIDATION_FAILED, null, Actor.system(), NOW);
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> message.assignRoute(Channel.SMS, PLAYMOBILE))
                .withMessageContaining("terminal status");
    }

    @Test
    @DisplayName("MP-03: a fallback chain requires content for every channel of the chain")
    void fallbackChainNeedsContentForEveryChannel() {
        // Arrange
        Recipient recipient =
                new Recipient(null, msisdn(), null, List.of(PushToken.of("device-token", PushPlatform.ANDROID)));
        ChannelPlan plan = ChannelPlan.fallbackChain(Channel.PUSH, Channel.SMS);

        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> Message.accept(
                        envelope("chain-1", TrafficClass.NOTIFICATION),
                        recipient,
                        plan,
                        MessageContents.of(PushContent.of("T", "B")),
                        null,
                        Timing.immediate(),
                        NOW))
                .withMessageContaining("requires content for channel SMS");

        Message message = Message.accept(
                envelope("chain-2", TrafficClass.NOTIFICATION),
                recipient,
                plan,
                MessageContents.of(PushContent.of("T", "B"), SmsContent.of("text")),
                null,
                Timing.immediate(),
                NOW);
        assertThat(message.deliverableChannels()).containsExactly(Channel.PUSH, Channel.SMS);
    }

    @Test
    @DisplayName("module choice falls back to the channels the recipient is reachable on")
    void moduleChoiceUsesReachableChannels() {
        // Arrange
        Message message = Message.accept(
                envelope("choice-1", TrafficClass.NOTIFICATION),
                Recipient.ofMsisdn(msisdn()),
                ChannelPlan.moduleChoice(),
                MessageContents.of(SmsContent.of("text"), PushContent.of("T", "B")),
                TemplateRef.of(TemplateCode.of("OTP_LOGIN")),
                Timing.immediate(),
                NOW);

        // Assert
        assertThat(message.deliverableChannels()).containsExactly(Channel.SMS);
        assertThat(message.isDeliverableOn(Channel.PUSH)).isFalse();
        assertThat(message.template()).isPresent();
    }

    @Test
    @DisplayName("module choice with candidates that carry no content is refused")
    void moduleChoiceCandidatesNeedContent() {
        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> Message.accept(
                        envelope("choice-2", TrafficClass.NOTIFICATION),
                        Recipient.ofMsisdn(msisdn()),
                        ChannelPlan.moduleChoice(List.of(Channel.EMAIL)),
                        MessageContents.of(SmsContent.of("text")),
                        null,
                        Timing.immediate(),
                        NOW))
                .withMessageContaining("carry no content");
    }

    @Test
    @DisplayName("the status history and the attempt list are not modifiable from outside")
    void collectionsAreUnmodifiable() {
        // Arrange
        Message message = smsMessage();
        List<StatusChange> history = message.statusHistory();
        StatusChange change = new StatusChange(MessageStatus.VALIDATED, null, null, Actor.system(), null, NOW);

        // Act + Assert
        assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() -> history.add(change));
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> message.attempts().clear());
    }

    @Test
    @DisplayName("messages are equal by identity")
    void equalityIsIdentityBased() {
        // Arrange
        Message message = smsMessage();

        // Act + Assert
        assertThat(message).isEqualTo(message).isNotEqualTo(smsMessage()).isNotEqualTo(null);
        assertThat(message.hashCode()).isEqualTo(message.id().hashCode());
        assertThat(message).hasToString("Message[" + message.id() + "]");
    }

    private static Message routedMessage() {
        Message message = smsMessage();
        Instant now = NOW;
        message.markValidated(Actor.system(), now);
        message.markRouted(Channel.SMS, PLAYMOBILE, Actor.system(), now);
        message.markQueued(Actor.system(), now);
        return message;
    }
}
