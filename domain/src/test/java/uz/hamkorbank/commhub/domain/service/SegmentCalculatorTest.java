package uz.hamkorbank.commhub.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;
import uz.hamkorbank.commhub.domain.model.content.SmsContent;
import uz.hamkorbank.commhub.domain.model.type.SmsEncoding;

/** SMS segmentation table of SRS §18.3 (MP-06). */
class SegmentCalculatorTest {

    private SegmentCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new SegmentCalculator();
    }

    @ParameterizedTest
    @CsvSource({
        "1,1", // one character
        "160,1", // single GSM-7 segment
        "161,2", // spills into a concatenated message
        "306,2", // §18.3: two parts
        "307,3",
        "459,3", // §18.3: three parts
        "612,4",
        "765,5",
        "918,6",
        "1071,7" // §18.3: seven parts
    })
    @DisplayName("§18.3: GSM-7 fits 160 characters in one part and 153 in each concatenated part")
    void gsm7SegmentBoundaries(int length, int expectedSegments) {
        // Arrange
        String text = "a".repeat(length);

        // Act
        SmsSegmentation segmentation = calculator.calculate(text);

        // Assert
        assertThat(segmentation.encoding()).isEqualTo(SmsEncoding.GSM7);
        assertThat(segmentation.characterCount()).isEqualTo(length);
        assertThat(segmentation.segments()).isEqualTo(expectedSegments);
    }

    @ParameterizedTest
    @CsvSource({"1,1", "70,1", "71,2", "134,2", "135,3", "201,3", "268,4", "335,5", "402,6", "469,7"})
    @DisplayName("§18.3: UCS-2 fits 70 characters in one part and 67 in each concatenated part")
    void ucs2SegmentBoundaries(int length, int expectedSegments) {
        // Arrange
        String text = "п".repeat(length);

        // Act
        SmsSegmentation segmentation = calculator.calculate(text);

        // Assert
        assertThat(segmentation.encoding()).isEqualTo(SmsEncoding.UCS2);
        assertThat(segmentation.characterCount()).isEqualTo(length);
        assertThat(segmentation.segments()).isEqualTo(expectedSegments);
    }

    @Test
    @DisplayName("§18.3: a single non-GSM-7 character switches the whole message to UCS-2")
    void oneCyrillicCharacterSwitchesTheWholeMessage() {
        // Arrange
        String text = "a".repeat(100) + "ў";

        // Act
        SmsSegmentation segmentation = calculator.calculate(text);

        // Assert
        assertThat(segmentation.encoding()).isEqualTo(SmsEncoding.UCS2);
        assertThat(segmentation.characterCount()).isEqualTo(101);
        assertThat(segmentation.segments()).isEqualTo(2);
    }

    @Test
    @DisplayName("§18.3: ^ { } \\ [ ~ ] | € count as two GSM-7 characters")
    void extendedGsm7CharactersCountTwice() {
        // Arrange
        String escapes = "^{}\\[~]|€";

        // Act
        SmsSegmentation segmentation = calculator.calculate(escapes);

        // Assert
        assertThat(segmentation.encoding()).isEqualTo(SmsEncoding.GSM7);
        assertThat(segmentation.characterCount()).isEqualTo(escapes.length() * 2);
        assertThat(segmentation.segments()).isEqualTo(1);
    }

    @Test
    @DisplayName("80 euro signs already fill a single GSM-7 segment")
    void escapeCharactersDriveTheSegmentBoundary() {
        // Act
        SmsSegmentation exactlyOne = calculator.calculate("€".repeat(80));
        SmsSegmentation spillsOver = calculator.calculate("€".repeat(81));

        // Assert
        assertThat(exactlyOne.characterCount()).isEqualTo(160);
        assertThat(exactlyOne.segments()).isEqualTo(1);
        assertThat(spillsOver.characterCount()).isEqualTo(162);
        assertThat(spillsOver.segments()).isEqualTo(2);
    }

    @Test
    @DisplayName("the GSM-7 alphabet covers the characters used by Uzbek and Russian latin text")
    void gsm7AlphabetCoverage() {
        // Act + Assert
        assertThat(calculator.detectEncoding("Hisobingizdan 100 000 UZS yechildi. Balans: 25 000 UZS"))
                .isEqualTo(SmsEncoding.GSM7);
        assertThat(calculator.detectEncoding("Kod: 1234\r\n@$¥èéùìòÇ_ÆæßÉ!\"#¤%&'()*+,-./:;<=>?¡§¿äöñüà"))
                .isEqualTo(SmsEncoding.GSM7);
        assertThat(calculator.detectEncoding("Код: 1234")).isEqualTo(SmsEncoding.UCS2);
        assertThat(calculator.detectEncoding("Emoji 😀")).isEqualTo(SmsEncoding.UCS2);
    }

    @Test
    @DisplayName("MP-06: an emoji is billed as two UCS-2 characters")
    void surrogatePairsCountAsTwoCharacters() {
        // Act
        SmsSegmentation segmentation = calculator.calculate("😀");

        // Assert
        assertThat(segmentation.characterCount()).isEqualTo(2);
        assertThat(segmentation.segments()).isEqualTo(1);
    }

    @Test
    @DisplayName("segmentation reports capacity, remaining characters and multipart flag")
    void segmentationExposesCapacity() {
        // Act
        SmsSegmentation single = calculator.calculate("a".repeat(150));
        SmsSegmentation multipart = calculator.calculate("a".repeat(200));

        // Assert
        assertThat(single.capacity()).isEqualTo(160);
        assertThat(single.remainingCharacters()).isEqualTo(10);
        assertThat(single.isMultipart()).isFalse();
        assertThat(multipart.capacity()).isEqualTo(306);
        assertThat(multipart.remainingCharacters()).isEqualTo(106);
        assertThat(multipart.isMultipart()).isTrue();
    }

    @Test
    @DisplayName("the calculation also accepts an SMS payload")
    void acceptsSmsContent() {
        // Arrange
        SmsContent content = SmsContent.of("Your code is 1234", "HAMKORBANK");

        // Act
        SmsSegmentation segmentation = calculator.calculate(content);

        // Assert
        assertThat(segmentation.segments()).isEqualTo(1);
        assertThat(segmentation.characterCount()).isEqualTo(17);
    }

    @Test
    @DisplayName("an empty text still occupies one segment, a missing text is rejected")
    void edgeCases() {
        // Act + Assert
        assertThat(calculator.calculate("").segments()).isEqualTo(1);
        assertThat(calculator.calculate("").characterCount()).isZero();
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> calculator.calculate((String) null));
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> calculator.calculate((SmsContent) null));
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> calculator.detectEncoding(null));
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> new SmsSegmentation(SmsEncoding.GSM7, 10, 0));
    }
}
