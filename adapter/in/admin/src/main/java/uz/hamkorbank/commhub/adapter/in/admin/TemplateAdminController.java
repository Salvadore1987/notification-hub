package uz.hamkorbank.commhub.adapter.in.admin;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.hamkorbank.commhub.adapter.in.admin.dto.PageResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.ProviderTemplateRequest;
import uz.hamkorbank.commhub.adapter.in.admin.dto.TemplateImportResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.TemplatePreviewRequest;
import uz.hamkorbank.commhub.adapter.in.admin.dto.TemplatePreviewResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.TemplateRequest;
import uz.hamkorbank.commhub.adapter.in.admin.dto.TemplateResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.TemplateSummaryResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.TemplateVersionRequest;
import uz.hamkorbank.commhub.adapter.in.admin.dto.TemplateVersionResponse;
import uz.hamkorbank.commhub.adapter.in.admin.mapper.AdminViewMapper;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminAuthority;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminPaging;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminValues;
import uz.hamkorbank.commhub.adapter.in.contract.InboundContractException;
import uz.hamkorbank.commhub.adapter.in.importer.TemplateCsvCodec;
import uz.hamkorbank.commhub.adapter.in.rest.security.AuthenticatedCaller;
import uz.hamkorbank.commhub.application.dto.TemplateImportResult;
import uz.hamkorbank.commhub.application.port.in.GetTemplates;
import uz.hamkorbank.commhub.application.port.in.ImportTemplates;
import uz.hamkorbank.commhub.application.port.in.ManageTemplates;
import uz.hamkorbank.commhub.application.port.in.command.CreateTemplateCommand;
import uz.hamkorbank.commhub.application.port.in.command.ImportTemplatesCommand;
import uz.hamkorbank.commhub.application.port.in.command.MapProviderTemplateCommand;
import uz.hamkorbank.commhub.application.port.in.command.SaveTemplateVersionCommand;
import uz.hamkorbank.commhub.application.port.in.command.TemplateStateCommand;
import uz.hamkorbank.commhub.application.port.in.command.TemplateVersionStateCommand;
import uz.hamkorbank.commhub.application.port.in.command.UnmapProviderTemplateCommand;
import uz.hamkorbank.commhub.application.port.in.command.UpdateTemplateCommand;
import uz.hamkorbank.commhub.application.port.in.query.TemplateListQuery;
import uz.hamkorbank.commhub.application.port.in.query.TemplatePreviewQuery;
import uz.hamkorbank.commhub.application.port.in.query.TemplateQuery;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;
import uz.hamkorbank.commhub.domain.model.type.TemplateCatalogStatus;
import uz.hamkorbank.commhub.domain.model.type.TemplateStatus;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * The template catalogue and its review workflow (§11.2 "Шаблоны", FR-4.1, FR-4.2, FR-4.4, FR-4.5).
 *
 * <p>Nothing here re-implements the two rules the domain owns, and both are the reason this screen has
 * the shape it does. A locale has exactly one sendable version, so publishing v2 archives v1 — the panel
 * does not archive anything itself. And only a {@code DRAFT} may be rewritten, so the editor is closed
 * on a version under review: that is what a reviewer is looking at, and a published one is what already
 * sent messages were rendered from.
 *
 * <p>The four-eyes rule of FR-4.2 is enforced by the use case from the authenticated actor and never
 * from a field: the author of a version is who wrote it, the reviewer of a publication is who pressed
 * publish, and they must differ. Which is why there is no {@code author} anywhere in these bodies.
 *
 * <p>Deleting a template is archiving it. Its code sits in the history of every message rendered from
 * it, so {@code DELETE} moves the card out of the working catalogue and stops it being sendable, and the
 * versions stay exactly where they were.
 */
@RestController
@RequestMapping(AdminApi.TEMPLATES)
public class TemplateAdminController {

    private final GetTemplates getTemplates;
    private final ManageTemplates manageTemplates;
    private final AuthenticatedCaller caller;
    private final AdminViewMapper mapper;
    private final ImportTemplates importTemplates;
    private final TemplateCsvCodec csvCodec;

    public TemplateAdminController(
            GetTemplates getTemplates,
            ManageTemplates manageTemplates,
            AuthenticatedCaller caller,
            AdminViewMapper mapper,
            ImportTemplates importTemplates,
            TemplateCsvCodec csvCodec) {
        this.getTemplates = Guard.notNull(getTemplates, "getTemplates");
        this.manageTemplates = Guard.notNull(manageTemplates, "manageTemplates");
        this.caller = Guard.notNull(caller, "caller");
        this.mapper = Guard.notNull(mapper, "mapper");
        this.importTemplates = Guard.notNull(importTemplates, "importTemplates");
        this.csvCodec = Guard.notNull(csvCodec, "csvCodec");
    }

    /** {@code GET /api/admin/v1/templates} — the catalogue page, without bodies (FR-4.1, UI-03). */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.TEMPLATE_CATALOGUE_READER)
    public PageResponse<TemplateSummaryResponse> list(
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String catalogStatus,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        TemplateListQuery query = new TemplateListQuery(
                AdminValues.optionalEnum(Channel.class, channel, "channel"),
                direction,
                AdminValues.optionalEnum(TemplateCatalogStatus.class, catalogStatus, "catalogStatus"),
                AdminPaging.limit(limit, TemplateListQuery.DEFAULT_LIMIT, TemplateListQuery.MAX_LIMIT),
                AdminPaging.offset(offset));
        List<TemplateSummaryResponse> items =
                getTemplates.list(query).stream().map(mapper::toTemplateSummary).toList();
        // The catalogue has no cheap count and does not need one: it is browsed, not paged through to a
        // known end, so the client pages until a short page comes back.
        return PageResponse.of(items, items.size() + (long) query.offset(), query.limit(), query.offset());
    }

    /** {@code GET /api/admin/v1/templates/{code}} — the card with every version (FR-4.1, FR-4.5). */
    @GetMapping(path = "/{code}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.TEMPLATE_MANAGER)
    public TemplateResponse card(@PathVariable String code) {
        return mapper.toTemplate(getTemplates.find(new TemplateQuery(templateCode(code))));
    }

    /** {@code POST /api/admin/v1/templates/{code}} — registers a card (FR-4.1). */
    @PostMapping(
            path = "/{code}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.TEMPLATE_MANAGER)
    public TemplateResponse create(@PathVariable String code, @RequestBody TemplateRequest request) {
        return mapper.toTemplate(manageTemplates.create(new CreateTemplateCommand(
                caller.actor(),
                templateCode(code),
                AdminValues.requiredEnum(Channel.class, request.channel(), "channel"),
                request.direction(),
                request.owner())));
    }

    /** {@code PUT /api/admin/v1/templates/{code}} — edits direction and owner (§18.4). */
    @PutMapping(
            path = "/{code}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.TEMPLATE_MANAGER)
    public TemplateResponse update(@PathVariable String code, @RequestBody TemplateRequest request) {
        return mapper.toTemplate(manageTemplates.update(
                new UpdateTemplateCommand(caller.actor(), templateCode(code), request.direction(), request.owner())));
    }

    /** {@code DELETE /api/admin/v1/templates/{code}} — archives the card (FR-4.1). */
    @DeleteMapping(path = "/{code}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.TEMPLATE_MANAGER)
    public TemplateResponse archive(
            @PathVariable String code, @RequestHeader(name = AdminApi.REASON_HEADER, required = false) String reason) {
        return mapper.toTemplate(manageTemplates.changeState(
                new TemplateStateCommand(caller.actor(), templateCode(code), TemplateCatalogStatus.ARCHIVED, reason)));
    }

    /** {@code POST /api/admin/v1/templates/{code}/restore} — brings it back into the catalogue (FR-4.1). */
    @PostMapping(path = "/{code}/restore", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.TEMPLATE_MANAGER)
    public TemplateResponse restore(
            @PathVariable String code, @RequestHeader(name = AdminApi.REASON_HEADER, required = false) String reason) {
        return mapper.toTemplate(manageTemplates.changeState(
                new TemplateStateCommand(caller.actor(), templateCode(code), TemplateCatalogStatus.ACTIVE, reason)));
    }

    /** {@code PUT /api/admin/v1/templates/{code}/versions} — writes a draft (FR-4.1). */
    @PutMapping(
            path = "/{code}/versions",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.TEMPLATE_MANAGER)
    public TemplateVersionResponse saveVersion(@PathVariable String code, @RequestBody TemplateVersionRequest request) {
        return mapper.toTemplateVersion(manageTemplates.saveVersion(new SaveTemplateVersionCommand(
                caller.actor(),
                templateCode(code),
                AdminValues.requiredEnum(ContentLocale.class, request.locale(), "locale"),
                request.version(),
                request.subject(),
                request.text(),
                request.html())));
    }

    /**
     * {@code POST /api/admin/v1/templates/{code}/versions/{locale}/{version}/state/{status}} — the review
     * workflow (FR-4.1, FR-4.2).
     *
     * <p>Publication needs a second person and the use case checks it. A version submitted for review by
     * the same login that wrote it is refused there, not here — the rule belongs with the aggregate that
     * knows who the author was.
     */
    @PostMapping(
            path = "/{code}/versions/{locale}/{version}/state/{status}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.TEMPLATE_MANAGER)
    public TemplateVersionResponse changeVersionState(
            @PathVariable String code,
            @PathVariable String locale,
            @PathVariable int version,
            @PathVariable String status) {
        return mapper.toTemplateVersion(manageTemplates.changeVersionState(new TemplateVersionStateCommand(
                caller.actor(),
                templateCode(code),
                AdminValues.requiredEnum(ContentLocale.class, locale, "locale"),
                version,
                AdminValues.requiredEnum(TemplateStatus.class, status, "status"))));
    }

    /** {@code POST /api/admin/v1/templates/{code}/preview} — rendered text, segments and cost (FR-4.4). */
    @PostMapping(
            path = "/{code}/preview",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.TEMPLATE_MANAGER)
    public TemplatePreviewResponse preview(@PathVariable String code, @RequestBody TemplatePreviewRequest request) {
        return mapper.toTemplatePreview(getTemplates.preview(new TemplatePreviewQuery(
                templateCode(code),
                AdminValues.requiredEnum(ContentLocale.class, request.locale(), "locale"),
                request.version(),
                request.variables())));
    }

    /** {@code PUT /api/admin/v1/templates/{code}/providers/{providerCode}} — records a mapping (FR-4.5). */
    @PutMapping(
            path = "/{code}/providers/{providerCode}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.TEMPLATE_MANAGER)
    public TemplateResponse mapProvider(
            @PathVariable String code,
            @PathVariable String providerCode,
            @RequestBody ProviderTemplateRequest request) {
        return mapper.toTemplate(manageTemplates.mapProviderTemplate(new MapProviderTemplateCommand(
                caller.actor(),
                templateCode(code),
                AdminValues.parseRequired(providerCode, "providerCode", ProviderCode::of),
                request.providerTemplateId(),
                request.approved())));
    }

    /** {@code DELETE /api/admin/v1/templates/{code}/providers/{providerCode}} — drops it (FR-4.5). */
    @DeleteMapping(path = "/{code}/providers/{providerCode}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.TEMPLATE_MANAGER)
    public TemplateResponse unmapProvider(@PathVariable String code, @PathVariable String providerCode) {
        return mapper.toTemplate(manageTemplates.unmapProviderTemplate(new UnmapProviderTemplateCommand(
                caller.actor(),
                templateCode(code),
                AdminValues.parseRequired(providerCode, "providerCode", ProviderCode::of))));
    }

    /**
     * {@code POST /api/admin/v1/templates/import} — a CSV upload of the catalogue (FR-4.6).
     *
     * <p>The same codec the file-based importer uses, so an upload and a mounted file are read
     * identically, and the same use case underneath — the import goes through the aggregate, is
     * idempotent by wording, reports bad rows and keeps importing.
     *
     * <p>{@code approver} is a query parameter and has to differ from the author, who is the
     * authenticated caller: publishing what you imported yourself is the four-eyes rule of FR-4.2 gone
     * missing at the one moment it matters most, which is a bulk load. Leave it out and the versions
     * arrive as drafts for somebody else to publish.
     */
    @PostMapping(path = "/import", consumes = AdminApi.TEXT_CSV, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.TEMPLATE_MANAGER)
    public TemplateImportResponse importCsv(@RequestBody String body, @RequestParam(required = false) String approver) {
        TemplateCsvCodec.Parsed parsed;
        try {
            parsed = csvCodec.parse(new StringReader(body), ',');
        } catch (IOException e) {
            throw InboundContractException.invalid("file", "could not be read", e);
        }
        Actor actor = caller.actor();
        TemplateImportResult result = importTemplates.importTemplates(
                new ImportTemplatesCommand(actor, authorOf(actor), approver, parsed.rows()));
        TemplateImportResponse response = mapper.toTemplateImport(result);
        return parsed.failures().isEmpty()
                ? response
                : new TemplateImportResponse(
                        response.created(),
                        response.imported(),
                        response.skipped(),
                        merged(response.failures(), parsed.failures()));
    }

    /**
     * Rows the file got wrong and rows the domain refused, in one list.
     *
     * <p>They are two different kinds of failure — a locale the Hub does not know versus a body the
     * aggregate would not accept — and an operator fixing a spreadsheet does not care which: what they
     * need is every line that did not go in, in one place.
     */
    private static List<TemplateImportResponse.FailureDto> merged(
            List<TemplateImportResponse.FailureDto> fromDomain, List<TemplateImportResult.Failure> fromFile) {
        List<TemplateImportResponse.FailureDto> failures = new ArrayList<>(fromDomain);
        fromFile.forEach(failure -> failures.add(
                new TemplateImportResponse.FailureDto(failure.code(), failure.locale(), failure.reason())));
        return List.copyOf(failures);
    }

    /** The import records its author by name; the Hub itself may not author a template (FR-4.2). */
    private static String authorOf(Actor actor) {
        if (actor.id() == null || actor.id().isBlank()) {
            throw InboundContractException.invalid(
                    "actor", "a template import needs a named author; the Hub may not author templates (FR-4.2)");
        }
        return actor.id();
    }

    private static TemplateCode templateCode(String raw) {
        return AdminValues.parseRequired(raw, "code", TemplateCode::of);
    }
}
