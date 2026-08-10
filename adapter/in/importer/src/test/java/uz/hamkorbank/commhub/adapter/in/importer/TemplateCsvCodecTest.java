package uz.hamkorbank.commhub.adapter.in.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.io.StringReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.application.port.in.command.ImportTemplatesCommand;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;

/** Reading the Bank's template export (FR-4.6). */
class TemplateCsvCodecTest {

    private final TemplateCsvCodec codec = new TemplateCsvCodec();

    @Test
    @DisplayName("FR-4.6: columns are taken by name, so an extra column in the sheet changes nothing")
    void readsColumnsByName() throws IOException {
        // Arrange
        String file = """
                owner;code;forecast_2026;channel;locale;text;direction
                retail-team;OTP_LOGIN;120000;SMS;RU;Код: {CODE};Чакана
                retail-team;OTP_LOGIN;90000;sms;uz;Kod: {CODE};Чакана
                """;

        // Act
        TemplateCsvCodec.Parsed parsed = codec.parse(new StringReader(file), ';');

        // Assert
        assertThat(parsed.failures()).isEmpty();
        assertThat(parsed.rows()).hasSize(2);
        ImportTemplatesCommand.Row first = parsed.rows().getFirst();
        assertThat(first.code()).isEqualTo("OTP_LOGIN");
        assertThat(first.channel()).isEqualTo(Channel.SMS);
        assertThat(first.locale()).isEqualTo(ContentLocale.RU);
        assertThat(first.text()).isEqualTo("Код: {CODE}");
        assertThat(first.direction()).isEqualTo("Чакана");
        assertThat(first.owner()).isEqualTo("retail-team");
        assertThat(parsed.rows().getLast().locale()).isEqualTo(ContentLocale.UZ);
    }

    @Test
    @DisplayName("FR-4.6: a quoted field carries the delimiter, line breaks and literal quotes")
    void readsQuotedFields() throws IOException {
        // Arrange — the text wraps and contains both the delimiter and a quote
        String file = "code;channel;locale;subject;text\r\n" + "PAYMENT_OK;EMAIL;RU;\"Оплата; успешна\";"
                + "\"Здравствуйте, {NAME}!\nСумма \"\"{AMOUNT}\"\" списана.\"\r\n";

        // Act
        TemplateCsvCodec.Parsed parsed = codec.parse(new StringReader(file), ';');

        // Assert
        assertThat(parsed.rows()).hasSize(1);
        ImportTemplatesCommand.Row row = parsed.rows().getFirst();
        assertThat(row.channel()).isEqualTo(Channel.EMAIL);
        assertThat(row.subject()).isEqualTo("Оплата; успешна");
        assertThat(row.text()).isEqualTo("Здравствуйте, {NAME}!\nСумма \"{AMOUNT}\" списана.");
    }

    @Test
    @DisplayName("FR-4.6: a row with an unknown channel or locale is reported, the rest of the file is kept")
    void reportsBadRowsAndKeepsTheRest() throws IOException {
        // Arrange
        String file = """
                code;channel;locale;text
                OTP_LOGIN;SMS;KZ;Код: {CODE}
                PAYMENT_OK;TELEX;RU;Оплата прошла
                DEBT_REMINDER;SMS;RU;Задолженность {AMOUNT}

                """;

        // Act
        TemplateCsvCodec.Parsed parsed = codec.parse(new StringReader(file), ';');

        // Assert
        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.rows().getFirst().code()).isEqualTo("DEBT_REMINDER");
        assertThat(parsed.failures()).hasSize(2);
        assertThat(parsed.failures().getFirst().reason()).contains("row 1", "unknown locale 'KZ'");
        assertThat(parsed.failures().getLast().reason()).contains("row 2", "unknown channel 'TELEX'");
    }

    @Test
    @DisplayName("FR-4.6: a file without the required columns is refused as a whole")
    void refusesFileWithoutRequiredColumns() {
        // Arrange
        String file = """
                code;channel;body
                OTP_LOGIN;SMS;Код: {CODE}
                """;

        // Act + Assert
        assertThatExceptionOfType(TemplateImportException.class)
                .isThrownBy(() -> codec.parse(new StringReader(file), ';'))
                .withMessageContaining("locale")
                .withMessageContaining("text");
    }

    @Test
    @DisplayName("an empty file, a header-only file and an unterminated quote are all refused")
    void refusesUnusableFiles() {
        // Act + Assert
        assertThatExceptionOfType(TemplateImportException.class)
                .isThrownBy(() -> codec.parse(new StringReader(""), ';'));
        assertThatExceptionOfType(TemplateImportException.class)
                .isThrownBy(() -> codec.parse(new StringReader("code;channel;locale;text\n"), ';'))
                .withMessageContaining("no rows");
        assertThatExceptionOfType(TemplateImportException.class)
                .isThrownBy(() ->
                        codec.parse(new StringReader("code;channel;locale;text\nOTP;SMS;RU;\"unterminated\n"), ';'))
                .withMessageContaining("quoted field");
    }

    @Test
    @DisplayName("a comma-separated file and a UTF-8 BOM are both read")
    void readsCommaSeparatedFileWithBom() throws IOException {
        // Arrange
        String file = "﻿code,channel,locale,text\nOTP_LOGIN,SMS,RU,Код: {CODE}\n";

        // Act
        TemplateCsvCodec.Parsed parsed = codec.parse(new StringReader(file), ',');

        // Assert
        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.rows().getFirst().code()).isEqualTo("OTP_LOGIN");
    }
}
