package uz.hamkorbank.commhub.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static uz.hamkorbank.commhub.domain.DomainFixtures.NOW;
import static uz.hamkorbank.commhub.domain.DomainFixtures.STREAM_ID;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;
import uz.hamkorbank.commhub.domain.model.type.ActorType;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.Priority;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.CorrelationId;
import uz.hamkorbank.commhub.domain.model.vo.DedupKey;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;

/** Envelope, actors, status history entries and template references (MP-01, ST-01, FR-4.3). */
class MessageEnvelopeTest {

    @Test
    @DisplayName("MP-01, TC-02: a single envelope derives its dedup key and priority")
    void singleEnvelopeDerivesDefaults() {
        // Act
        MessageEnvelope envelope =
                MessageEnvelope.single(STREAM_ID, ExternalMessageId.of("abc0000001"), TrafficClass.CRITICAL_OTP);

        // Assert
        assertThat(envelope.priority()).isEqualTo(Priority.REALTIME);
        assertThat(envelope.dedupKey()).isEqualTo(DedupKey.of("mobile-app:abc0000001"));
        assertThat(envelope.isBatched()).isFalse();
        assertThat(envelope.batchIdOptional()).isEmpty();
        assertThat(envelope.correlationId()).isNotNull();
    }

    @Test
    @DisplayName("FR-1.6: a batch item carries its batch id")
    void batchedEnvelopeCarriesTheBatchId() {
        // Arrange
        BatchId batchId = BatchId.newId();

        // Act
        MessageEnvelope envelope = MessageEnvelope.batched(
                STREAM_ID, ExternalMessageId.of("batch-item-1"), TrafficClass.NOTIFICATION, batchId);

        // Assert
        assertThat(envelope.isBatched()).isTrue();
        assertThat(envelope.batchIdOptional()).contains(batchId);
        assertThat(envelope.priority()).isEqualTo(Priority.LOW);
    }

    @Test
    @DisplayName("explicit priority, dedup key and correlation id override the derived ones")
    void explicitValuesOverrideDefaults() {
        // Arrange
        MessageEnvelope envelope =
                MessageEnvelope.single(STREAM_ID, ExternalMessageId.of("abc1"), TrafficClass.NOTIFICATION);

        // Act
        MessageEnvelope updated = envelope.withPriority(Priority.HIGH)
                .withDedupKey(DedupKey.of("custom-key"))
                .withCorrelationId(CorrelationId.of("trace-1"));

        // Assert
        assertThat(updated.priority()).isEqualTo(Priority.HIGH);
        assertThat(updated.dedupKey()).isEqualTo(DedupKey.of("custom-key"));
        assertThat(updated.correlationId()).isEqualTo(CorrelationId.of("trace-1"));
        assertThat(updated.id()).isEqualTo(envelope.id());
    }

    @Test
    @DisplayName("a mandatory envelope field must not be missing")
    void mandatoryFieldsAreChecked() {
        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(
                        () -> MessageEnvelope.single(null, ExternalMessageId.of("abc1"), TrafficClass.NOTIFICATION));
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> MessageEnvelope.single(STREAM_ID, ExternalMessageId.of("abc1"), null));
    }

    @Test
    @DisplayName("ST-01: actors describe the system, an operator, a provider and a source system")
    void actorsDescribeTheOrigin() {
        // Act + Assert
        assertThat(Actor.system().type()).isEqualTo(ActorType.SYSTEM);
        assertThat(Actor.system().id()).isNull();
        assertThat(Actor.system()).hasToString("SYSTEM");
        assertThat(Actor.operator("ivan")).hasToString("OPERATOR:ivan");
        assertThat(Actor.provider("PLAYMOBILE").type()).isEqualTo(ActorType.PROVIDER);
        assertThat(Actor.sourceSystem("mobile-app").type()).isEqualTo(ActorType.SOURCE_SYSTEM);
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> Actor.operator(" "));
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> new Actor(null, "x"));
    }

    @Test
    @DisplayName("ST-01: a status change records reason, detail, actor and provider")
    void statusChangeCarriesTheContext() {
        // Act
        StatusChange change = new StatusChange(MessageStatus.VALIDATED, null, null, Actor.system(), null, NOW);

        // Assert
        assertThat(change.reasonOptional()).isEmpty();
        assertThat(change.detailsOptional()).isEmpty();
        assertThat(change.providerCodeOptional()).isEmpty();
        assertThat(change.occurredAt()).isEqualTo(NOW);
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> new StatusChange(MessageStatus.VALIDATED, null, null, null, null, NOW));
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> new StatusChange(null, null, null, Actor.system(), null, NOW));
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> new StatusChange(
                        MessageStatus.VALIDATED,
                        null,
                        "x".repeat(StatusChange.MAX_DETAILS_LENGTH + 1),
                        Actor.system(),
                        null,
                        NOW));
    }

    @Test
    @DisplayName("FR-4.3: a template reference carries locale and merge variables")
    void templateReferenceCarriesVariables() {
        // Arrange
        TemplateCode code = TemplateCode.of("OTP_LOGIN");

        // Act
        TemplateRef ref = TemplateRef.of(code, ContentLocale.RU, Map.of("NAME", "IVAN"));

        // Assert
        assertThat(ref.localeOptional()).contains(ContentLocale.RU);
        assertThat(ref.variable("NAME")).contains("IVAN");
        assertThat(ref.variable("MISSING")).isEmpty();
        assertThat(TemplateRef.of(code).localeOptional()).isEmpty();
        assertThat(TemplateRef.of(code, ContentLocale.EN).variables()).isEmpty();
        assertThat(ref.withVariables(Map.of("NAME", "PETR")).variable("NAME")).contains("PETR");
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> TemplateRef.of(null));
    }

    @Test
    @DisplayName("traffic classes carry their SLA-relevant behaviour (§6.2, FR-3.2, FR-5.3)")
    void trafficClassBehaviour() {
        // Act + Assert
        assertThat(TrafficClass.CRITICAL_OTP.defaultPriority()).isEqualTo(Priority.REALTIME);
        assertThat(TrafficClass.CRITICAL_OTP.stoppableByDefault()).isFalse();
        assertThat(TrafficClass.CRITICAL_OTP.respectsQuietHours()).isFalse();
        assertThat(TrafficClass.NOTIFICATION.respectsQuietHours()).isTrue();
        assertThat(TrafficClass.NOTIFICATION.respectsFrequencyCapping()).isTrue();
        assertThat(TrafficClass.TRANSACTIONAL.respectsFrequencyCapping()).isFalse();
        assertThat(TrafficClass.TRANSACTIONAL.stoppableByDefault()).isTrue();
        assertThat(Priority.REALTIME.isAtLeast(Priority.HIGH)).isTrue();
        assertThat(Priority.LOW.isAtLeast(Priority.NORMAL)).isFalse();
        assertThat(Priority.NORMAL.rank()).isEqualTo(1);
    }
}
