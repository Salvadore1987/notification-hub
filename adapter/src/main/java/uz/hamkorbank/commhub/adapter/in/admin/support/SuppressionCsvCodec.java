package uz.hamkorbank.commhub.adapter.in.admin.support;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.adapter.in.admin.dto.SuppressionRequest;
import uz.hamkorbank.commhub.adapter.in.contract.InboundContractException;
import uz.hamkorbank.commhub.adapter.in.importer.CsvRecords;

/**
 * Reads a suppression list upload into rows (§11.2 "Suppression list", FR-5.1).
 *
 * <p>Columns by header name: {@code channel} and {@code reason} are required, and one of {@code address}
 * or {@code clientId} has to be there — which the endpoint checks per row, because a file that mixes the
 * two is normal (a complaint gives an address, a court order gives a customer).
 *
 * <p>Nothing is validated here beyond the shape. An address is checked by the value object of its
 * channel inside the use case and hashed there (DB-04): a mistyped number has to fail as a rejected row
 * rather than become a hash that matches nothing for the rest of its life, and the import reports it
 * that way — one bad line in a file of four hundred is a line somebody fixes, not a file they upload
 * again.
 */
@Component
public class SuppressionCsvCodec {

    private static final String CHANNEL = "channel";
    private static final String ADDRESS = "address";
    private static final String CLIENT_ID = "clientid";
    private static final String REASON = "reason";
    private static final String VALID_UNTIL = "validuntil";
    private static final List<String> REQUIRED = List.of(CHANNEL, REASON);

    /** Rows of one upload, in file order so a reported failure names the line the operator sees. */
    public List<SuppressionRequest> parse(Reader reader, char delimiter) throws IOException {
        List<List<String>> records = read(reader, delimiter);
        if (records.isEmpty()) {
            throw InboundContractException.invalid("file", "the suppression file is empty");
        }
        Map<String, Integer> columns = header(records.getFirst());
        List<SuppressionRequest> rows = new ArrayList<>();
        for (int index = 1; index < records.size(); index++) {
            List<String> record = records.get(index);
            if (CsvRecords.isBlank(record)) {
                continue;
            }
            rows.add(new SuppressionRequest(
                    CsvRecords.value(record, columns, CHANNEL),
                    CsvRecords.value(record, columns, ADDRESS),
                    CsvRecords.value(record, columns, CLIENT_ID),
                    CsvRecords.value(record, columns, REASON),
                    CsvRecords.value(record, columns, VALID_UNTIL)));
        }
        if (rows.isEmpty()) {
            throw InboundContractException.invalid("file", "the suppression file has a header but no rows");
        }
        return rows;
    }

    private static List<List<String>> read(Reader reader, char delimiter) throws IOException {
        try {
            return CsvRecords.read(reader, delimiter);
        } catch (IllegalArgumentException e) {
            throw InboundContractException.invalid("file", e.getMessage(), e);
        }
    }

    private static Map<String, Integer> header(List<String> record) {
        try {
            return CsvRecords.header(record, REQUIRED);
        } catch (IllegalArgumentException e) {
            throw InboundContractException.invalid("file", e.getMessage(), e);
        }
    }
}
