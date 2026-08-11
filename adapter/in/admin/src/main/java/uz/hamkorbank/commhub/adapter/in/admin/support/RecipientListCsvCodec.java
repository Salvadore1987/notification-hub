package uz.hamkorbank.commhub.adapter.in.admin.support;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.adapter.in.admin.dto.ImportResultResponse;
import uz.hamkorbank.commhub.adapter.in.contract.InboundContractException;
import uz.hamkorbank.commhub.adapter.in.importer.CsvRecords;
import uz.hamkorbank.commhub.application.port.in.command.OperatorBatchCommand;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.PushPlatform;
import uz.hamkorbank.commhub.domain.model.vo.ClientId;
import uz.hamkorbank.commhub.domain.model.vo.EmailAddress;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.Msisdn;
import uz.hamkorbank.commhub.domain.model.vo.PushToken;
import uz.hamkorbank.commhub.domain.model.vo.Recipient;

/**
 * The uploaded recipient list of a panel send (ADR-0038, FR-1.6).
 *
 * <p>Reserved column names address the recipient; <b>every other column is a merge variable</b>, its
 * header being the variable's name. That inversion — unknown columns are data rather than noise — is
 * what lets an operator export a payroll sheet and send from it unchanged, and it is why the header is
 * read with the case of the file preserved.
 *
 * <p>Rows and failures come back together, following the template import rather than the suppression
 * one: a file of fifty thousand rows must not be refused because of one mistyped number.
 *
 * <p>When a row carries no {@code externalId}, its identity is derived from the file's own hash and the
 * line number. Re-uploading the same file inside the dedup window is then a no-op instead of a second
 * SMS to every customer on it (FR-1.5) — which is what a double click on "Отправить" would otherwise be.
 */
@Component
public class RecipientListCsvCodec {

    /** Columns that address the message rather than fill it in; matched without regard to case. */
    private static final Set<String> RESERVED =
            Set.of("msisdn", "email", "pushtoken", "pushplatform", "clientid", "externalid");

    private static final Map<Channel, String> ADDRESS_COLUMN =
            Map.of(Channel.SMS, "msisdn", Channel.EMAIL, "email", Channel.PUSH, "pushtoken");

    /** Parses the file for one channel; the address column must be the one that channel uses. */
    public Parsed parse(Reader reader, char delimiter, Channel channel) throws IOException {
        List<List<String>> records = read(reader, delimiter);
        if (records.isEmpty()) {
            throw InboundContractException.invalid("file", "is empty");
        }
        Map<String, Integer> columns = CsvRecords.headerAsGiven(records.getFirst());
        Map<String, Integer> lowerCased = lowerCased(columns);
        String addressColumn = ADDRESS_COLUMN.get(channel);
        if (!lowerCased.containsKey(addressColumn)) {
            throw InboundContractException.invalid(
                    "file", "has no %s column, which channel %s needs".formatted(addressColumn, channel));
        }

        String fileHash = hashOf(records);
        List<OperatorBatchCommand.Item> rows = new ArrayList<>();
        List<ImportResultResponse.FailureDto> failures = new ArrayList<>();
        for (int index = 1; index < records.size(); index++) {
            List<String> record = records.get(index);
            int line = index + 1;
            if (CsvRecords.isBlank(record)) {
                continue;
            }
            try {
                rows.add(itemOf(record, columns, lowerCased, channel, fileHash, line));
            } catch (RuntimeException e) {
                failures.add(new ImportResultResponse.FailureDto(line, e.getMessage()));
            }
        }
        if (rows.isEmpty() && failures.isEmpty()) {
            throw InboundContractException.invalid("file", "has a header but no rows");
        }
        return new Parsed(rows, failures);
    }

    private static OperatorBatchCommand.Item itemOf(
            List<String> record,
            Map<String, Integer> columns,
            Map<String, Integer> lowerCased,
            Channel channel,
            String fileHash,
            int line) {
        Recipient recipient = recipientOf(record, lowerCased, channel);
        String externalId = CsvRecords.value(record, lowerCased, "externalid");
        return new OperatorBatchCommand.Item(
                ExternalMessageId.of(externalId == null ? derivedId(fileHash, line) : externalId),
                recipient,
                variablesOf(record, columns));
    }

    private static Recipient recipientOf(List<String> record, Map<String, Integer> columns, Channel channel) {
        ClientId clientId = parse(CsvRecords.value(record, columns, "clientid"), ClientId::of);
        return switch (channel) {
            case SMS -> new Recipient(clientId, Msisdn.normalize(required(record, columns, "msisdn")), null, List.of());
            case EMAIL -> new Recipient(clientId, null, EmailAddress.of(required(record, columns, "email")), List.of());
            case PUSH ->
                new Recipient(
                        clientId,
                        null,
                        null,
                        List.of(PushToken.of(required(record, columns, "pushtoken"), pushPlatformOf(record, columns))));
        };
    }

    private static PushPlatform pushPlatformOf(List<String> record, Map<String, Integer> columns) {
        String platform = CsvRecords.value(record, columns, "pushplatform");
        if (platform == null) {
            throw new IllegalArgumentException("pushPlatform is required for a push recipient");
        }
        return PushPlatform.valueOf(platform.toUpperCase(Locale.ROOT));
    }

    /** Everything that is not a reserved column is this row's merge data, header case preserved. */
    private static Map<String, String> variablesOf(List<String> record, Map<String, Integer> columns) {
        Map<String, String> variables = new LinkedHashMap<>();
        columns.forEach((name, index) -> {
            if (RESERVED.contains(name.toLowerCase(Locale.ROOT)) || index >= record.size()) {
                return;
            }
            String value = record.get(index).trim();
            if (!value.isEmpty()) {
                variables.put(name, value);
            }
        });
        return variables;
    }

    private static List<List<String>> read(Reader reader, char delimiter) throws IOException {
        try {
            return CsvRecords.read(reader, delimiter);
        } catch (IllegalArgumentException e) {
            throw InboundContractException.invalid("file", e.getMessage(), e);
        }
    }

    private static Map<String, Integer> lowerCased(Map<String, Integer> columns) {
        Map<String, Integer> result = new LinkedHashMap<>();
        columns.forEach((name, index) -> result.putIfAbsent(name.toLowerCase(Locale.ROOT), index));
        return result;
    }

    private static String required(List<String> record, Map<String, Integer> columns, String column) {
        String value = CsvRecords.value(record, columns, column);
        if (value == null) {
            throw new IllegalArgumentException(column + " is empty");
        }
        return value;
    }

    private static <T> T parse(String value, java.util.function.Function<String, T> factory) {
        return value == null ? null : factory.apply(value);
    }

    /** Identity of a row in a file that carries none: the file itself plus the line it sits on. */
    private static String derivedId(String fileHash, int line) {
        return fileHash + "-" + line;
    }

    private static String hashOf(List<List<String>> records) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (List<String> record : records) {
                digest.update(String.join("", record).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    /**
     * What the file yielded.
     *
     * @param failures rows that could not be read; reported with their line so the operator finds them
     *     in the spreadsheet they uploaded
     */
    public record Parsed(List<OperatorBatchCommand.Item> rows, List<ImportResultResponse.FailureDto> failures) {}
}
