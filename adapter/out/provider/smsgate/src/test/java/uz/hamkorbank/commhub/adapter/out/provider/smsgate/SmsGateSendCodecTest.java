package uz.hamkorbank.commhub.adapter.out.provider.smsgate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import uz.hamkorbank.commhub.adapter.out.provider.smsgate.SmsGateProperties.Sending;
import uz.hamkorbank.commhub.adapter.out.provider.smsgate.SmsGateSendCodec.SmsGateAnswer;
import uz.hamkorbank.commhub.adapter.out.provider.smsgate.SmsGateSendCodec.SmsGateItem;
import uz.hamkorbank.commhub.application.port.out.provider.SmsSubmission;
import uz.hamkorbank.commhub.application.port.out.provider.SubmissionContext;
import uz.hamkorbank.commhub.domain.model.Timing;
import uz.hamkorbank.commhub.domain.model.content.SmsContent;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.Priority;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.CorrelationId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.Msisdn;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;

/** The wire shape of the SMS Gate requests and answers (§9.2, §18.2). */
class SmsGateSendCodecTest {

    private static final SmsGateCredentials CREDENTIALS = new SmsGateCredentials("hamkor", "k3y");

    private final SmsGateJson json = new SmsGateJson();

    private final SmsGateSendCodec codec = new SmsGateSendCodec(json);

    @Test
    @DisplayName("§9.2: /api/v2/send carries login, key, sender, phone, text and weight")
    void writesSingleSend() {
        // Act
        JsonNode root = json.readOrNull(codec.encodeSend(
                CREDENTIALS, submission("Kod: 4821", TrafficClass.CRITICAL_OTP), new Sending("3700", null)));

        // Assert
        assertThat(root.get("login").asString()).isEqualTo("hamkor");
        assertThat(root.get("key").asString()).isEqualTo("k3y");
        assertThat(root.get("sender").asString()).isEqualTo("3700");
        assertThat(root.get("phone").asString()).isEqualTo("998901234567");
        assertThat(root.get("text").asString()).isEqualTo("Kod: 4821");
        assertThat(root.get("weight").asInt()).isEqualTo(10);
    }

    @Test
    @DisplayName("SG-01: no timing and no template block — the Hub renders and schedules, the provider does not")
    void sendsNeitherTimingNorTemplate() {
        // Act
        JsonNode root = json.readOrNull(
                codec.encodeSend(CREDENTIALS, submission("Text", TrafficClass.TRANSACTIONAL), Sending.defaults()));

        // Assert
        assertThat(root.get("timing")).isNull();
        assertThat(root.get("template-id")).isNull();
    }

    @Test
    @DisplayName("§9.2: /api/v2/send_msgs carries one sender and weight for the chunk and a 1-based seq per element")
    void writesBatchSend() {
        // Arrange
        List<SmsSubmission> submissions =
                List.of(submission("A", TrafficClass.NOTIFICATION), submission("B", TrafficClass.NOTIFICATION));

        // Act
        JsonNode root = json.readOrNull(codec.encodeSendBatch(CREDENTIALS, submissions, new Sending("3700", null)));

        // Assert
        assertThat(root.get("weight").asInt()).isEqualTo(3);
        assertThat(root.get("messages").size()).isEqualTo(2);
        assertThat(root.get("messages").get(0).get("seq").asInt()).isEqualTo(1);
        assertThat(root.get("messages").get(1).get("seq").asInt()).isEqualTo(2);
        assertThat(root.get("messages").get(1).get("text").asString()).isEqualTo("B");
    }

    @Test
    @DisplayName("SG-03: /api/v2/search is queried by number and a Unix timestamp")
    void writesSearchRequest() {
        // Act
        JsonNode root = json.readOrNull(codec.encodeSearch(CREDENTIALS, "998901234567", 1_785_000_000L));

        // Assert
        assertThat(root.get("phone").asString()).isEqualTo("998901234567");
        assertThat(root.get("date").asLong()).isEqualTo(1_785_000_000L);
    }

    @Test
    @DisplayName("§9.2: an accepted send answers with status.code 0 and the provider-assigned id")
    void readsAcceptedAnswer() {
        // Act
        SmsGateAnswer answer = codec.readAnswer(
                        "{\"status\":{\"code\":0,\"description\":\"success\"},\"id\":98765," + "\"parts\":1}")
                .orElseThrow();

        // Assert
        assertThat(answer.isSuccess()).isTrue();
        assertThat(answer.id()).isEqualTo("98765");
        assertThat(answer.description()).isEqualTo("success");
    }

    @Test
    @DisplayName("§18.2: a refusal answers with its code, and the wording comes from the table when absent")
    void readsRefusedAnswer() {
        // Act
        SmsGateAnswer answer = codec.readAnswer("{\"status\":{\"code\":13}}").orElseThrow();

        // Assert
        assertThat(answer.isSuccess()).isFalse();
        assertThat(answer.code()).isEqualTo("13");
        assertThat(answer.description()).isEqualTo("wrong key");
    }

    @Test
    @DisplayName("§9.2: the status code is read whether it is nested, flat, a number or a string")
    void readsTheStatusCodeInEitherShape() {
        // Act + Assert
        assertThat(codec.readAnswer("{\"code\":\"0\"}").orElseThrow().isSuccess())
                .isTrue();
        assertThat(codec.readAnswer("{\"status\":\"1\"}").orElseThrow().code()).isEqualTo("1");
        assertThat(codec.readAnswer("{\"nothing\":true}")).isEmpty();
        assertThat(codec.readAnswer("not json")).isEmpty();
    }

    @Test
    @DisplayName("SG-02: a chunk answers per element, and an element may report a state word instead of a code")
    void readsBatchAnswerWithCodesAndStates() {
        // Act
        List<SmsGateItem> items = codec.readBatchAnswer("""
                {"status":{"code":0},"messages":[
                  {"seq":1,"id":1001,"code":0,"parts":1},
                  {"seq":2,"status":"blacklist"},
                  {"seq":3,"status":"ok","id":1003}
                ]}
                """);

        // Assert
        assertThat(items).hasSize(3);
        assertThat(items.get(0).isSuccess()).isTrue();
        assertThat(items.get(0).id()).isEqualTo("1001");
        assertThat(items.get(1).code()).isEqualTo("20");
        assertThat(items.get(1).isSuccess()).isFalse();
        assertThat(items.get(2).isSuccess()).isTrue();
    }

    @Test
    @DisplayName("§9.2: an answer without a messages array is a verdict on the whole chunk")
    void readsNoElementsFromAChunkWideRefusal() {
        // Act + Assert
        assertThat(codec.readBatchAnswer("{\"status\":{\"code\":13}}")).isEmpty();
    }

    @Test
    @DisplayName("SG-03: the search answer is read from messages, from result or from a bare array")
    void readsSearchAnswerInSeveralShapes() {
        // Act
        List<SmsGateItem> nested = codec.readSearch("{\"messages\":[{\"id\":7,\"code\":4}]}");
        List<SmsGateItem> underResult = codec.readSearch("{\"result\":[{\"id\":8,\"code\":2}]}");
        List<SmsGateItem> bare = codec.readSearch("[{\"id\":9,\"code\":6}]");

        // Assert
        assertThat(nested).singleElement().satisfies(entry -> {
            assertThat(entry.id()).isEqualTo("7");
            assertThat(entry.code()).isEqualTo("4");
        });
        assertThat(underResult.getFirst().code()).isEqualTo("2");
        assertThat(bare.getFirst().id()).isEqualTo("9");
        assertThat(codec.readSearch("{}")).isEmpty();
    }

    @Test
    @DisplayName("SG-04: the credentials never render themselves, whatever logs them")
    void credentialsDoNotRenderThemselves() {
        // Act + Assert
        assertThat(CREDENTIALS.toString()).doesNotContain("hamkor").doesNotContain("k3y");
    }

    private static SmsSubmission submission(String text, TrafficClass trafficClass) {
        return new SmsSubmission(
                new ProviderRef(
                        ProviderId.newId(), ProviderCode.of("SMSGATE"), Channel.SMS, AdapterType.of("smsgate-http")),
                MessageId.newId(),
                null,
                Msisdn.of("998901234567"),
                SmsContent.of(text),
                Timing.immediate(),
                null,
                new SubmissionContext(trafficClass, Priority.NORMAL, CorrelationId.of("corr-1"), false));
    }
}
