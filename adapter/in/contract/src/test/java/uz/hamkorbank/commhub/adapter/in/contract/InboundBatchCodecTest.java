package uz.hamkorbank.commhub.adapter.in.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.adapter.in.contract.mapper.InboundPayloadMapperImpl;
import uz.hamkorbank.commhub.application.port.in.command.AddBatchItemsCommand;
import uz.hamkorbank.commhub.application.port.in.command.CreateBatchCommand;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;

/** The batch documents of §8.2 → their commands (FR-1.6). */
class InboundBatchCodecTest {

    private static final BatchId BATCH_ID = BatchId.newId();

    private static final StreamId STREAM_ID = StreamId.of("ibank-retail");

    private final InboundBatchCodec codec = new InboundBatchCodec(new InboundJson(), new InboundPayloadMapperImpl());

    @Test
    @DisplayName("POST /batches: reads the header of a batch")
    void readsTheHeader() {
        // Arrange
        String header = """
                {
                  "streamId": "ibank-retail",
                  "channel": "SMS",
                  "trafficClass": "NOTIFICATION",
                  "expectedTotal": 25000,
                  "template": { "id": "promo.summer", "locale": "RU" },
                  "timing": { "ttlSeconds": 86400 },
                  "test": false
                }
                """;

        // Act
        CreateBatchCommand command = codec.readHeader(header);

        // Assert
        assertThat(command.streamId()).isEqualTo(STREAM_ID);
        assertThat(command.channel()).isEqualTo(Channel.SMS);
        assertThat(command.trafficClass()).isEqualTo(TrafficClass.NOTIFICATION);
        assertThat(command.expectedTotal()).isEqualTo(25_000L);
        assertThat(command.template().code().value()).isEqualTo("PROMO.SUMMER");
        assertThat(command.batchId()).isNull();
        assertThat(command.test()).isFalse();
    }

    @Test
    @DisplayName("A header without a channel is refused, naming the field")
    void refusesAHeaderWithoutAChannel() {
        // Act + Assert
        assertThatThrownBy(() -> codec.readHeader("{ \"streamId\": \"ibank-retail\" }"))
                .isInstanceOf(InboundContractException.class)
                .satisfies(thrown ->
                        assertThat(((InboundContractException) thrown).field()).isEqualTo("channel"));
    }

    @Test
    @DisplayName("POST /batches/{id}/items: reads a chunk into per-item submissions")
    void readsAChunkOfItems() {
        // Arrange
        String chunk = """
                { "items": [
                    { "externalMessageId": "b-1", "recipient": { "msisdn": "998901234567" },
                      "template": { "id": "promo.summer", "variables": { "name": "Aziz" } } },
                    { "externalMessageId": "b-2", "recipient": { "msisdn": "998901234568" },
                      "content": { "sms": { "text": "hi" } } }
                ] }
                """;

        // Act
        AddBatchItemsCommand command = codec.readItems(chunk, BATCH_ID, STREAM_ID);

        // Assert
        assertThat(command.batchId()).isEqualTo(BATCH_ID);
        assertThat(command.items()).hasSize(2);
        assertThat(command.items().getFirst().variables()).containsEntry("name", "Aziz");
        assertThat(command.items().getLast().contents().channels()).containsExactly(Channel.SMS);
    }

    @Test
    @DisplayName("FR-1.6: an item that only carries variables keeps them, the template being the header's")
    void keepsTheVariablesOfAnItemWithoutATemplateId() {
        // Arrange — документированная форма заливки: шаблон назван в заголовке рассылки, элемент
        // несёт значения своей строки и ничего больше
        String chunk = """
                { "items": [
                    { "externalMessageId": "b-1", "recipient": { "msisdn": "998901234567" },
                      "template": { "variables": { "CODE": "1234" } } }
                ] }
                """;

        // Act
        AddBatchItemsCommand command = codec.readItems(chunk, BATCH_ID, STREAM_ID);

        // Assert — своего шаблона у элемента нет, а переменные не потеряны
        assertThat(command.items().getFirst().template()).isNull();
        assertThat(command.items().getFirst().variables()).containsEntry("CODE", "1234");
    }

    @Test
    @DisplayName("§8.2: a chunk beyond 10 000 items is refused before anything is mapped")
    void refusesAnOversizedChunk() {
        // Arrange
        String items = IntStream.rangeClosed(0, InboundBatchCodec.MAX_ITEMS_PER_CHUNK)
                .mapToObj(index -> """
                        { "externalMessageId": "b-%d", "recipient": { "msisdn": "998901234567" },
                          "content": { "sms": { "text": "hi" } } }""".formatted(index))
                .collect(Collectors.joining(","));

        // Act + Assert
        assertThatThrownBy(() -> codec.readItems("{ \"items\": [%s] }".formatted(items), BATCH_ID, STREAM_ID))
                .isInstanceOf(InboundContractException.class)
                .hasMessageContaining("at most");
    }

    @Test
    @DisplayName("An empty chunk is refused: it would register progress for nothing")
    void refusesAnEmptyChunk() {
        // Act + Assert
        assertThatThrownBy(() -> codec.readItems("{ \"items\": [] }", BATCH_ID, STREAM_ID))
                .isInstanceOf(InboundContractException.class)
                .satisfies(thrown ->
                        assertThat(((InboundContractException) thrown).field()).isEqualTo("items"));
    }

    @Test
    @DisplayName("A bad item names its position in the chunk")
    void namesThePositionOfABadItem() {
        // Arrange
        String chunk = """
                { "items": [
                    { "externalMessageId": "b-1", "recipient": { "msisdn": "998901234567" },
                      "content": { "sms": { "text": "hi" } } },
                    { "recipient": { "msisdn": "998901234568" }, "content": { "sms": { "text": "hi" } } }
                ] }
                """;

        // Act + Assert
        assertThatThrownBy(() -> codec.readItems(chunk, BATCH_ID, STREAM_ID))
                .isInstanceOf(InboundContractException.class)
                .satisfies(thrown -> assertThat(((InboundContractException) thrown).field())
                        .isEqualTo("items[1].externalMessageId"));
    }
}
