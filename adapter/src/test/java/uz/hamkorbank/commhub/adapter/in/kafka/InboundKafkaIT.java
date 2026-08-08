package uz.hamkorbank.commhub.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import uz.hamkorbank.commhub.adapter.in.BatchActions;
import uz.hamkorbank.commhub.adapter.in.contract.InboundBatchCodec;
import uz.hamkorbank.commhub.adapter.in.contract.InboundJson;
import uz.hamkorbank.commhub.adapter.in.contract.InboundMessageCodec;
import uz.hamkorbank.commhub.adapter.in.contract.mapper.InboundPayloadMapper;
import uz.hamkorbank.commhub.adapter.in.contract.mapper.InboundPayloadMapperImpl;
import uz.hamkorbank.commhub.adapter.out.kafka.KafkaBroker;
import uz.hamkorbank.commhub.adapter.out.kafka.KafkaConnectionProperties;
import uz.hamkorbank.commhub.adapter.out.kafka.KafkaOutboundProperties;
import uz.hamkorbank.commhub.adapter.out.kafka.KafkaProducerConfig;
import uz.hamkorbank.commhub.application.dto.BatchAcceptedResult;
import uz.hamkorbank.commhub.application.dto.BatchControlResult;
import uz.hamkorbank.commhub.application.dto.BatchItemsResult;
import uz.hamkorbank.commhub.application.dto.BatchProgressDto;
import uz.hamkorbank.commhub.application.dto.SubmitMessageResult;
import uz.hamkorbank.commhub.application.port.in.PauseBatch;
import uz.hamkorbank.commhub.application.port.in.ResumeBatch;
import uz.hamkorbank.commhub.application.port.in.StartBatch;
import uz.hamkorbank.commhub.application.port.in.StopBatch;
import uz.hamkorbank.commhub.application.port.in.SubmitBatch;
import uz.hamkorbank.commhub.application.port.in.SubmitMessage;
import uz.hamkorbank.commhub.application.port.in.command.AddBatchItemsCommand;
import uz.hamkorbank.commhub.application.port.in.command.BatchActionCommand;
import uz.hamkorbank.commhub.application.port.in.command.CreateBatchCommand;
import uz.hamkorbank.commhub.application.port.in.command.SubmitMessageCommand;
import uz.hamkorbank.commhub.domain.model.type.BatchStatus;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;

/**
 * The Kafka ingress against a real broker (§8.1, IK-01, IK-04, TC-01, QA-03).
 *
 * <p>What a unit test cannot show is what this is here for: that the containers really start on the
 * topics of the configuration, that the traffic class a message ends up with is the one its topic
 * dictates, and — the part that matters operationally — that a record nobody can parse leaves the
 * partition instead of blocking it forever.
 */
@Tag("integration")
@SpringJUnitConfig(InboundKafkaIT.IngressTestConfig.class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@TestPropertySource(
        properties = {
            "commhub.kafka.inbound.topics.critical=" + InboundKafkaIT.CRITICAL_TOPIC,
            "commhub.kafka.inbound.topics.transactional=" + InboundKafkaIT.TRANSACTIONAL_TOPIC,
            "commhub.kafka.inbound.topics.notification=" + InboundKafkaIT.NOTIFICATION_TOPIC,
            "commhub.kafka.inbound.topics.batch-control=" + InboundKafkaIT.BATCH_CONTROL_TOPIC
        })
class InboundKafkaIT {

    static final String CRITICAL_TOPIC = "it.inbound.critical.v1";
    static final String TRANSACTIONAL_TOPIC = "it.inbound.transactional.v1";
    static final String NOTIFICATION_TOPIC = "it.inbound.notification.v1";
    static final String BATCH_CONTROL_TOPIC = "it.inbound.batch-control.v1";
    static final String PARSE_ERROR_TOPIC = "it.inbound.parse-error.v1";

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final RecordingSubmitMessage messages;
    private final RecordingSubmitBatch batches;

    InboundKafkaIT(RecordingSubmitMessage messages, RecordingSubmitBatch batches) {
        this.messages = messages;
        this.batches = batches;
    }

    @Test
    @DisplayName("IK-01: a document on the critical topic reaches the pipeline as CRITICAL_OTP")
    void consumesTheCriticalTopic() throws Exception {
        // Arrange
        String externalId = "otp-" + UUID.randomUUID();
        publish(CRITICAL_TOPIC, "998901234567", message(externalId, "NOTIFICATION"));

        // Act
        SubmitMessageCommand command = messages.awaiting(externalId);

        // Assert: the topic wins over the trafficClass the document claims (TC-01)
        assertThat(command).isNotNull();
        assertThat(command.delivery().trafficClass()).isEqualTo(TrafficClass.CRITICAL_OTP);
        assertThat(command.recipient().msisdn().value()).isEqualTo("998901234567");
    }

    @Test
    @DisplayName("IK-01: the notification topic feeds the bulk pool with its own class")
    void consumesTheNotificationTopic() throws Exception {
        // Arrange
        String externalId = "bulk-" + UUID.randomUUID();
        publish(NOTIFICATION_TOPIC, "batch-1", message(externalId, null));

        // Act
        SubmitMessageCommand command = messages.awaiting(externalId);

        // Assert
        assertThat(command).isNotNull();
        assertThat(command.delivery().trafficClass()).isEqualTo(TrafficClass.NOTIFICATION);
    }

    @Test
    @DisplayName("IK-04: a poison pill lands on the parse-error topic and the partition keeps moving")
    void routesAPoisonPillToTheParseErrorTopic() throws Exception {
        // Arrange
        String poison = "{ \"streamId\": \"ibank-retail\", this is not json";
        String survivor = "after-" + UUID.randomUUID();

        // Act
        publish(TRANSACTIONAL_TOPIC, "998901234567", poison);
        publish(TRANSACTIONAL_TOPIC, "998901234567", message(survivor, null));

        // Assert: the bad record is out of the way…
        List<ConsumerRecord<String, String>> parseErrors = drain(PARSE_ERROR_TOPIC, 1);
        assertThat(parseErrors).isNotEmpty();
        assertThat(parseErrors.getFirst().value()).isEqualTo(poison);
        assertThat(header(parseErrors.getFirst(), InboundErrorHandlerConfig.HEADER_ORIGIN_TOPIC))
                .isEqualTo(TRANSACTIONAL_TOPIC);

        // …and the record behind it was processed all the same
        assertThat(messages.awaiting(survivor)).isNotNull();
    }

    @Test
    @DisplayName("IK-01: the batch-control topic creates a batch through its use case")
    void consumesTheBatchControlTopic() throws Exception {
        // Arrange
        String command = """
                { "action": "CREATE", "batch": { "streamId": "ibank-retail", "channel": "SMS",
                  "expectedTotal": 3, "batchId": "%s" } }
                """.formatted(UUID.randomUUID());

        // Act
        publish(BATCH_CONTROL_TOPIC, "batch-1", command);

        // Assert
        assertThat(batches.awaitCreate()).isNotNull();
    }

    private static String message(String externalMessageId, String trafficClass) {
        return """
                {
                  "schemaVersion": "1.0",
                  "streamId": "ibank-retail",
                  "externalMessageId": "%s",
                  %s
                  "recipient": { "msisdn": "998901234567" },
                  "content": { "sms": { "text": "Kod: 123456" } }
                }
                """.formatted(
                externalMessageId, trafficClass == null ? "" : "\"trafficClass\": \"%s\",".formatted(trafficClass));
    }

    private static void publish(String topic, String key, String value) throws Exception {
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KafkaBroker.bootstrapServers(),
                ProducerConfig.ACKS_CONFIG,
                "all");
        try (KafkaProducer<String, String> producer =
                new KafkaProducer<>(config, new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(topic, key, value)).get(30, TimeUnit.SECONDS);
        }
    }

    private static List<ConsumerRecord<String, String>> drain(String topic, int expected) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KafkaBroker.bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                "it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false);
        List<ConsumerRecord<String, String>> collected = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (collected.size() < expected && System.nanoTime() < deadline) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(300));
                polled.records(topic).forEach(collected::add);
            }
        }
        return collected;
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** The ingress with real containers, a real broker, and recorders in place of the use cases. */
    @Configuration
    @Import({KafkaProducerConfig.class, KafkaConsumerConfig.class, InboundErrorHandlerConfig.class})
    static class IngressTestConfig {

        @Bean
        static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        KafkaConnectionProperties kafkaConnectionProperties() {
            return KafkaConnectionProperties.of(KafkaBroker.bootstrapServers());
        }

        @Bean
        KafkaOutboundProperties kafkaOutboundProperties() {
            return KafkaOutboundProperties.defaults();
        }

        @Bean
        KafkaInboundProperties kafkaInboundProperties() {
            return new KafkaInboundProperties(
                    true,
                    "it-ingress-" + UUID.randomUUID(),
                    new KafkaInboundProperties.Topics(
                            CRITICAL_TOPIC,
                            TRANSACTIONAL_TOPIC,
                            NOTIFICATION_TOPIC,
                            BATCH_CONTROL_TOPIC,
                            PARSE_ERROR_TOPIC),
                    new KafkaInboundProperties.Concurrency(1, 1, 1, 1),
                    10,
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(5));
        }

        @Bean
        InboundJson inboundJson() {
            return new InboundJson();
        }

        @Bean
        InboundPayloadMapper inboundPayloadMapper() {
            return new InboundPayloadMapperImpl();
        }

        @Bean
        InboundMessageCodec inboundMessageCodec(InboundJson json, InboundPayloadMapper mapper) {
            return new InboundMessageCodec(json, mapper);
        }

        @Bean
        InboundBatchCodec inboundBatchCodec(InboundJson json, InboundPayloadMapper mapper) {
            return new InboundBatchCodec(json, mapper);
        }

        @Bean
        RecordingSubmitMessage recordingSubmitMessage() {
            return new RecordingSubmitMessage();
        }

        @Bean
        RecordingSubmitBatch recordingSubmitBatch() {
            return new RecordingSubmitBatch();
        }

        @Bean
        BatchActions batchActions(RecordingSubmitBatch batches) {
            return new BatchActions(batches, batches, batches, batches);
        }

        @Bean
        InboundMessageListener inboundMessageListener(InboundMessageCodec codec, SubmitMessage submitMessage) {
            return new InboundMessageListener(codec, submitMessage);
        }

        @Bean
        BatchControlListener batchControlListener(
                InboundJson json, InboundBatchCodec codec, SubmitBatch submitBatch, BatchActions actions) {
            return new BatchControlListener(json, codec, submitBatch, actions);
        }
    }

    /** Records what reached the pipeline; the tests wait on it instead of sleeping. */
    static class RecordingSubmitMessage implements SubmitMessage {

        private final BlockingQueue<SubmitMessageCommand> received = new LinkedBlockingQueue<>();

        @Override
        public SubmitMessageResult submit(SubmitMessageCommand command) {
            received.add(command);
            return SubmitMessageResult.accepted(MessageId.newId(), MessageStatus.QUEUED);
        }

        /** Waits for the submission of one external id, ignoring whatever else the topics carry. */
        SubmitMessageCommand awaiting(String externalMessageId) throws InterruptedException {
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                SubmitMessageCommand command = received.poll(1, TimeUnit.SECONDS);
                if (command != null && command.externalMessageId().value().equals(externalMessageId)) {
                    return command;
                }
            }
            return null;
        }
    }

    /** The batch use cases, recorded the same way. */
    static class RecordingSubmitBatch implements SubmitBatch, StartBatch, PauseBatch, ResumeBatch, StopBatch {

        private static final BatchProgressDto NO_PROGRESS = new BatchProgressDto(0, 0, 0, 0, 0, 0.0);

        private final BlockingQueue<CreateBatchCommand> created = new LinkedBlockingQueue<>();

        @Override
        public BatchAcceptedResult create(CreateBatchCommand command) {
            created.add(command);
            return new BatchAcceptedResult(
                    command.batchIdOptional().orElseGet(BatchId::newId), BatchStatus.ACCEPTED, command.expectedTotal());
        }

        @Override
        public BatchItemsResult addItems(AddBatchItemsCommand command) {
            return new BatchItemsResult(command.batchId(), command.items().size(), 0, List.of(), NO_PROGRESS);
        }

        @Override
        public BatchControlResult start(BatchActionCommand command) {
            return new BatchControlResult(command.batchId(), BatchStatus.PROCESSING, NO_PROGRESS);
        }

        @Override
        public BatchControlResult pause(BatchActionCommand command) {
            return new BatchControlResult(command.batchId(), BatchStatus.PAUSED, NO_PROGRESS);
        }

        @Override
        public BatchControlResult resume(BatchActionCommand command) {
            return new BatchControlResult(command.batchId(), BatchStatus.PROCESSING, NO_PROGRESS);
        }

        @Override
        public BatchControlResult stop(BatchActionCommand command) {
            return new BatchControlResult(command.batchId(), BatchStatus.STOPPED, NO_PROGRESS);
        }

        CreateBatchCommand awaitCreate() throws InterruptedException {
            return created.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }
    }
}
