package uz.hamkorbank.commhub.adapter.in.callback;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.hamkorbank.commhub.application.dto.ProcessProviderStatusResult;
import uz.hamkorbank.commhub.application.exception.NotFoundException;
import uz.hamkorbank.commhub.application.port.in.ProcessProviderStatus;
import uz.hamkorbank.commhub.application.port.in.command.ProviderStatusCommand;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Delivery reports pushed by the providers (PM-02, SG-02, §18.1, §18.2).
 *
 * <p>One endpoint for all of them, with the provider in the path: what differs between Playmobile's
 * {@code /status} push and SMS Gate's FEEDBACK post is the payload, and that is exactly what the
 * per-provider translator handles. Everything else — who may call, how the report reaches the use
 * case, what the provider is told — is the same and lives here.
 *
 * <p><strong>The answer is 200 whenever the report was understood</strong>, including when it changed
 * nothing. Providers retry on anything else, and a late duplicate report for a message that is already
 * delivered is not a failure — it is the normal shape of an at-least-once callback (AD-06). A report
 * for a message nobody knows is answered 200 too: repeating it will not make the message appear, and
 * making the provider retry forever costs both sides.
 */
@RestController
@RequestMapping("${commhub.callback.base:/api/callbacks}")
public class ProviderCallbackController {

    private static final Logger LOG = LoggerFactory.getLogger(ProviderCallbackController.class);

    private final CallbackGuard guard;
    private final ProviderCallbackRegistry registry;
    private final ProcessProviderStatus processStatus;

    public ProviderCallbackController(
            CallbackGuard guard, ProviderCallbackRegistry registry, ProcessProviderStatus processStatus) {
        this.guard = Guard.notNull(guard, "guard");
        this.registry = Guard.notNull(registry, "registry");
        this.processStatus = Guard.notNull(processStatus, "processStatus");
    }

    /**
     * {@code POST /api/callbacks/{providerCode}} — one delivery report, or a batch of them.
     *
     * <p>The body is taken raw and the parameters separately, because the providers disagree about
     * where a report lives: Playmobile sends JSON, SMS Gate posts form fields (§18.1, §18.2).
     */
    @PostMapping(path = "/{providerCode}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> receive(
            @PathVariable String providerCode,
            @RequestBody(required = false) String body,
            @RequestParam Map<String, String> parameters,
            HttpServletRequest request) {
        guard.check(providerCode, request);
        ProviderCallbackTranslator translator = registry.find(providerCode)
                .orElseThrow(() -> NotFoundException.of("callback translator for provider", providerCode));

        List<ProviderStatusCommand> reports = translator.translate(body, parameters == null ? Map.of() : parameters);
        int applied = 0;
        for (ProviderStatusCommand report : reports) {
            ProcessProviderStatusResult result = processStatus.process(report);
            if (result.applied()) {
                applied++;
            } else {
                LOG.debug(
                        "Report of {} for {} changed nothing: {}",
                        providerCode,
                        report.providerMessageIdOptional().map(Object::toString).orElse("-"),
                        result.detail());
            }
        }
        return ResponseEntity.ok(acknowledgement(reports.size(), applied));
    }

    /**
     * What the provider is told back. Deliberately minimal — the counts help operations correlate a
     * push with what it did, and nothing here tells an unauthenticated caller anything about a message.
     */
    private static Map<String, Object> acknowledgement(int received, int applied) {
        Map<String, Object> body = new HashMap<>();
        body.put("received", received);
        body.put("applied", applied);
        body.put("status", "OK");
        return body;
    }
}
