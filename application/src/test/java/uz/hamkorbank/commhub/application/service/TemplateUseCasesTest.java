package uz.hamkorbank.commhub.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.NOW;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.smsProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.hamkorbank.commhub.application.dto.TemplateImportResult;
import uz.hamkorbank.commhub.application.dto.TemplatePreviewView;
import uz.hamkorbank.commhub.application.dto.TemplateSummary;
import uz.hamkorbank.commhub.application.dto.TemplateVersionView;
import uz.hamkorbank.commhub.application.dto.TemplateView;
import uz.hamkorbank.commhub.application.exception.ConfigurationConflictException;
import uz.hamkorbank.commhub.application.exception.NotFoundException;
import uz.hamkorbank.commhub.application.mapper.TemplateMapperImpl;
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
import uz.hamkorbank.commhub.application.port.out.AuditEntry;
import uz.hamkorbank.commhub.application.port.out.AuditPort;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.ProviderConfigRepository;
import uz.hamkorbank.commhub.application.port.out.TemplateRepository;
import uz.hamkorbank.commhub.application.service.pipeline.PanDetector;
import uz.hamkorbank.commhub.application.service.support.ConfigAuditor;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.Tariff;
import uz.hamkorbank.commhub.domain.model.Template;
import uz.hamkorbank.commhub.domain.model.TemplateVersion;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;
import uz.hamkorbank.commhub.domain.model.type.TemplateCatalogStatus;
import uz.hamkorbank.commhub.domain.model.type.TemplateStatus;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;
import uz.hamkorbank.commhub.domain.model.vo.TemplateId;
import uz.hamkorbank.commhub.domain.model.vo.TemplateVersionId;
import uz.hamkorbank.commhub.domain.service.SegmentCalculator;

/** Template catalogue: CRUD, review workflow, provider mapping, preview and import (FR-4.1…FR-4.6). */
class TemplateUseCasesTest {

    private static final TemplateCode CODE = TemplateCode.of("OTP_LOGIN");
    private static final Actor AUTHOR = Actor.operator("ivanov");
    private static final Actor REVIEWER = Actor.operator("petrov");

    private TemplateRepository templates;
    private ProviderConfigRepository providers;
    private AuditPort audit;

    private TemplateConfigService configService;
    private TemplateQueryService queryService;
    private TemplateImportService importService;

    @BeforeEach
    void setUp() {
        templates = mock(TemplateRepository.class);
        providers = mock(ProviderConfigRepository.class);
        audit = mock(AuditPort.class);
        ClockPort clock = mock(ClockPort.class);
        when(clock.now()).thenReturn(NOW);
        when(templates.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ConfigAuditor auditor = new ConfigAuditor(audit, clock);
        TemplateMapperImpl mapper = new TemplateMapperImpl();
        configService = new TemplateConfigService(templates, providers, clock, mapper, auditor, new PanDetector());
        queryService = new TemplateQueryService(templates, providers, new SegmentCalculator(), mapper);
        importService = new TemplateImportService(templates, clock, auditor, new PanDetector());
    }

    @Test
    @DisplayName("FR-4.1: a new card is stored, audited and comes back without versions")
    void createsTemplate() {
        // Arrange
        when(templates.findByCode(CODE)).thenReturn(Optional.empty());

        // Act
        TemplateView view =
                configService.create(new CreateTemplateCommand(AUTHOR, CODE, Channel.SMS, "Чакана", "retail-team"));

        // Assert
        assertThat(view.code()).isEqualTo(CODE);
        assertThat(view.catalogStatus()).isEqualTo(TemplateCatalogStatus.ACTIVE);
        assertThat(view.versions()).isEmpty();
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).write(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo("template.create");
        assertThat(entry.getValue().before()).isNull();
    }

    @Test
    @DisplayName("FR-4.1: a duplicate code is a conflict, not a second card")
    void refusesDuplicateCode() {
        // Arrange
        when(templates.findByCode(CODE)).thenReturn(Optional.of(template()));

        // Act + Assert
        assertThatExceptionOfType(ConfigurationConflictException.class)
                .isThrownBy(() -> configService.create(
                        new CreateTemplateCommand(AUTHOR, CODE, Channel.SMS, "Чакана", "retail-team")));
    }

    @Test
    @DisplayName("FR-4.1: an unknown code is a 404, whichever operation asked for it")
    void refusesUnknownCode() {
        // Arrange
        when(templates.findByCode(CODE)).thenReturn(Optional.empty());

        // Act + Assert
        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> configService.update(new UpdateTemplateCommand(AUTHOR, CODE, "МСБ", null)));
        assertThatExceptionOfType(NotFoundException.class).isThrownBy(() -> queryService.find(new TemplateQuery(CODE)));
    }

    @Test
    @DisplayName("FR-4.1: saving without a version number opens the next draft of the locale")
    void appendsNextDraft() {
        // Arrange
        Template template = template();
        template.addVersion(publishedVersion(template, "Код: {CODE}"));
        when(templates.findByCode(CODE)).thenReturn(Optional.of(template));

        // Act
        TemplateVersionView view = configService.saveVersion(
                new SaveTemplateVersionCommand(AUTHOR, CODE, ContentLocale.RU, null, null, "Ваш код: {CODE}"));

        // Assert
        assertThat(view.version()).isEqualTo(2);
        assertThat(view.status()).isEqualTo(TemplateStatus.DRAFT);
        assertThat(view.review().createdBy()).isEqualTo("ivanov");
        assertThat(view.variables()).containsExactly("CODE");
        assertThat(template.publishedVersion(ContentLocale.RU).orElseThrow().version())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("FR-4.1: saving with a version number rewrites that draft and only while it is a draft")
    void rewritesDraft() {
        // Arrange
        Template template = template();
        TemplateVersion draft = draft(template, "Код: {CODE}");
        template.addVersion(draft);
        when(templates.findByCode(CODE)).thenReturn(Optional.of(template));

        // Act
        TemplateVersionView view = configService.saveVersion(
                new SaveTemplateVersionCommand(AUTHOR, CODE, ContentLocale.RU, 1, null, "Код: {CODE}, до {TTL}"));

        // Assert
        assertThat(view.version()).isEqualTo(1);
        assertThat(view.variables()).containsExactly("CODE", "TTL");
        assertThat(template.versions()).hasSize(1);

        // Act + Assert — under review the text is frozen
        draft.submitForReview();
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> configService.saveVersion(
                        new SaveTemplateVersionCommand(AUTHOR, CODE, ContentLocale.RU, 1, null, "ещё раз")));
    }

    @Test
    @DisplayName("SEC-05: a template body carrying a card number is refused, whatever the sending mode is")
    void refusesCardNumberInTemplateBody() {
        // Arrange
        Template template = template();
        when(templates.findByCode(CODE)).thenReturn(Optional.of(template));

        // Act + Assert
        assertThatExceptionOfType(ConfigurationConflictException.class)
                .isThrownBy(() -> configService.saveVersion(new SaveTemplateVersionCommand(
                        AUTHOR, CODE, ContentLocale.RU, null, null, "Карта 4111 1111 1111 1111 пополнена")))
                .withMessageContaining("SEC-05");
        assertThat(template.versions()).isEmpty();
    }

    @Test
    @DisplayName("SEC-05, FR-4.6: an imported row with a card number is reported, the rest of the file goes in")
    void importReportsCardNumberRow() {
        // Arrange
        when(templates.findByCode(CODE)).thenReturn(Optional.of(template()));
        when(templates.findByCode(TemplateCode.of("PAYMENT_OK"))).thenReturn(Optional.empty());

        // Act
        TemplateImportResult result = importService.importTemplates(new ImportTemplatesCommand(
                REVIEWER,
                "legacy-import",
                null,
                List.of(
                        row("OTP_LOGIN", ContentLocale.RU, "Карта 4111 1111 1111 1111"),
                        row("PAYMENT_OK", ContentLocale.RU, "Оплата прошла"))));

        // Assert
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().getFirst().reason()).contains("SEC-05");
        assertThat(result.imported()).isEqualTo(1);
    }

    @Test
    @DisplayName("FR-4.2: the author cannot publish their own version, a second person can")
    void enforcesMakerChecker() {
        // Arrange
        Template template = template();
        TemplateVersion draft = draft(template, "Код: {CODE}");
        template.addVersion(draft);
        draft.submitForReview();
        when(templates.findByCode(CODE)).thenReturn(Optional.of(template));
        TemplateVersionStateCommand publishByAuthor =
                new TemplateVersionStateCommand(AUTHOR, CODE, ContentLocale.RU, 1, TemplateStatus.PUBLISHED);

        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> configService.changeVersionState(publishByAuthor));
        assertThat(draft.status()).isEqualTo(TemplateStatus.ON_REVIEW);

        // Act
        TemplateVersionView view = configService.changeVersionState(
                new TemplateVersionStateCommand(REVIEWER, CODE, ContentLocale.RU, 1, TemplateStatus.PUBLISHED));

        // Assert
        assertThat(view.status()).isEqualTo(TemplateStatus.PUBLISHED);
        assertThat(view.review().reviewedBy()).contains("petrov");
        assertThat(view.review().publishedAtOptional()).contains(NOW);
    }

    @Test
    @DisplayName("FR-4.2: an unnamed actor — the Hub itself — may not author or publish a template")
    void refusesSystemActor() {
        // Arrange
        Template template = template();
        when(templates.findByCode(CODE)).thenReturn(Optional.of(template));

        // Act + Assert
        assertThatExceptionOfType(ConfigurationConflictException.class)
                .isThrownBy(() -> configService.saveVersion(new SaveTemplateVersionCommand(
                        Actor.system(), CODE, ContentLocale.RU, null, null, "Код: {CODE}")));
    }

    @Test
    @DisplayName("FR-4.1: publishing a new version retires the previous one")
    void publishingRetiresPreviousVersion() {
        // Arrange
        Template template = template();
        TemplateVersion first = publishedVersion(template, "Код: {CODE}");
        template.addVersion(first);
        TemplateVersion second = draft(template, "Ваш код: {CODE}");
        template.addVersion(second);
        second.submitForReview();
        when(templates.findByCode(CODE)).thenReturn(Optional.of(template));

        // Act
        configService.changeVersionState(
                new TemplateVersionStateCommand(REVIEWER, CODE, ContentLocale.RU, 2, TemplateStatus.PUBLISHED));

        // Assert
        assertThat(first.status()).isEqualTo(TemplateStatus.ARCHIVED);
        assertThat(template.publishedVersion(ContentLocale.RU)).contains(second);
    }

    @Test
    @DisplayName("FR-4.1: an archived card keeps its versions and refuses new ones")
    void archivesCard() {
        // Arrange
        Template template = template();
        template.addVersion(publishedVersion(template, "Код: {CODE}"));
        when(templates.findByCode(CODE)).thenReturn(Optional.of(template));

        // Act
        TemplateView view = configService.changeState(
                new TemplateStateCommand(AUTHOR, CODE, TemplateCatalogStatus.ARCHIVED, "заменён на OTP_LOGIN_V2"));

        // Assert
        assertThat(view.catalogStatus()).isEqualTo(TemplateCatalogStatus.ARCHIVED);
        assertThat(view.versions()).hasSize(1);
        assertThatExceptionOfType(ConfigurationConflictException.class)
                .isThrownBy(() -> configService.saveVersion(
                        new SaveTemplateVersionCommand(AUTHOR, CODE, ContentLocale.RU, null, null, "новый текст")));

        // Act + Assert — and back
        assertThat(configService
                        .changeState(new TemplateStateCommand(AUTHOR, CODE, TemplateCatalogStatus.ACTIVE, null))
                        .catalogStatus())
                .isEqualTo(TemplateCatalogStatus.ACTIVE);
    }

    @Test
    @DisplayName("FR-4.5: a provider mapping is recorded, kept per provider and dropped on request")
    void mapsToProviderTemplate() {
        // Arrange
        Template template = template();
        when(templates.findByCode(CODE)).thenReturn(Optional.of(template));
        when(providers.findProviderByCode(ProviderCode.of("PLAYMOBILE")))
                .thenReturn(Optional.of(smsProvider("PLAYMOBILE")));

        // Act
        TemplateView view = configService.mapProviderTemplate(
                new MapProviderTemplateCommand(AUTHOR, CODE, ProviderCode.of("PLAYMOBILE"), "pm-otp-login", false));

        // Assert
        assertThat(view.providerMappings()).hasSize(1);
        assertThat(view.providerMappings().getFirst().approved()).isFalse();

        // Act — the operator records the operator's approval (FR-4.5)
        view = configService.mapProviderTemplate(
                new MapProviderTemplateCommand(AUTHOR, CODE, ProviderCode.of("PLAYMOBILE"), "pm-otp-login", true));

        // Assert
        assertThat(view.providerMappings()).hasSize(1);
        assertThat(view.providerMappings().getFirst().approved()).isTrue();

        // Act + Assert
        assertThat(configService
                        .unmapProviderTemplate(
                                new UnmapProviderTemplateCommand(AUTHOR, CODE, ProviderCode.of("PLAYMOBILE")))
                        .providerMappings())
                .isEmpty();
        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> configService.unmapProviderTemplate(
                        new UnmapProviderTemplateCommand(AUTHOR, CODE, ProviderCode.of("PLAYMOBILE"))));
    }

    @Test
    @DisplayName("FR-4.5: a template cannot be mapped onto an unknown provider or one of another channel")
    void refusesImpossibleProviderMapping() {
        // Arrange
        Template template = template();
        when(templates.findByCode(CODE)).thenReturn(Optional.of(template));
        when(providers.findProviderByCode(ProviderCode.of("GHOST"))).thenReturn(Optional.empty());
        Provider emailProvider = Provider.register(
                ProviderId.newId(),
                ProviderCode.of("SMTP"),
                Channel.EMAIL,
                AdapterType.of("smtp"),
                Provider.Settings.defaults());
        when(providers.findProviderByCode(ProviderCode.of("SMTP"))).thenReturn(Optional.of(emailProvider));

        // Act + Assert
        assertThatExceptionOfType(ConfigurationConflictException.class)
                .isThrownBy(() -> configService.mapProviderTemplate(
                        new MapProviderTemplateCommand(AUTHOR, CODE, ProviderCode.of("GHOST"), "x", false)));
        assertThatExceptionOfType(ConfigurationConflictException.class)
                .isThrownBy(() -> configService.mapProviderTemplate(
                        new MapProviderTemplateCommand(AUTHOR, CODE, ProviderCode.of("SMTP"), "x", false)));
    }

    @Test
    @DisplayName("FR-4.4: the preview segments the rendered text and prices it per provider, cheapest first")
    void previewsSegmentsAndCost() {
        // Arrange
        Template template = template();
        template.addVersion(publishedVersion(template, "Здравствуйте, {NAME}! Ваш код {CODE}"));
        when(templates.findByCode(CODE)).thenReturn(Optional.of(template));
        Provider cheap = smsProvider("SMSGATE");
        Provider expensive = smsProvider("PLAYMOBILE");
        expensive.updateTariff(Tariff.perSegment(Money.of("200", "UZS")));
        when(providers.findProviders(Channel.SMS)).thenReturn(List.of(expensive, cheap));

        // Act
        TemplatePreviewView view =
                queryService.preview(new TemplatePreviewQuery(CODE, ContentLocale.RU, null, Map.of("NAME", "ИВАН")));

        // Assert
        assertThat(view.rendered().text()).isEqualTo("Здравствуйте, ИВАН! Ваш код {CODE}");
        assertThat(view.missingVariables()).containsExactly("CODE");
        assertThat(view.version().status()).isEqualTo(TemplateStatus.PUBLISHED);
        assertThat(view.segmentationOptional()).isPresent();
        assertThat(view.segmentationOptional().orElseThrow().segments()).isEqualTo(1);
        assertThat(view.costs()).hasSize(2);
        assertThat(view.costs().getFirst().providerCode()).isEqualTo(ProviderCode.of("SMSGATE"));
        assertThat(view.costs().getFirst().selectable()).isTrue();
    }

    @Test
    @DisplayName("FR-4.4: the preview reaches a draft — that is when an operator needs it")
    void previewsADraft() {
        // Arrange
        Template template = template();
        template.addVersion(draft(template, "Черновик {CODE}"));
        when(templates.findByCode(CODE)).thenReturn(Optional.of(template));
        when(providers.findProviders(Channel.SMS)).thenReturn(List.of());

        // Act
        TemplatePreviewView view =
                queryService.preview(new TemplatePreviewQuery(CODE, ContentLocale.RU, null, Map.of()));

        // Assert
        assertThat(view.version().status()).isEqualTo(TemplateStatus.DRAFT);
        assertThat(view.costs()).isEmpty();
    }

    @Test
    @DisplayName("FR-4.1: the listing carries the published locales and no bodies")
    void listsCatalogue() {
        // Arrange
        Template template = template();
        template.addVersion(publishedVersion(template, "Код: {CODE}"));
        when(templates.findAll(Channel.SMS, null, TemplateCatalogStatus.ACTIVE, 50, 0))
                .thenReturn(List.of(template));

        // Act
        List<TemplateSummary> summaries = queryService.list(TemplateListQuery.ofChannel(Channel.SMS));

        // Assert
        assertThat(summaries).hasSize(1);
        assertThat(summaries.getFirst().publishedLocales()).containsExactly(ContentLocale.RU);
    }

    @Test
    @DisplayName("FR-4.6: an import builds one card per code with a version per locale, published by the approver")
    void importsTheExistingBase() {
        // Arrange
        when(templates.findByCode(any())).thenReturn(Optional.empty());
        ImportTemplatesCommand command = new ImportTemplatesCommand(
                REVIEWER,
                "legacy-import",
                "petrov",
                List.of(
                        row("OTP_LOGIN", ContentLocale.RU, "Код: {CODE}"),
                        row("OTP_LOGIN", ContentLocale.UZ, "Kod: {CODE}"),
                        row("PAYMENT_OK", ContentLocale.RU, "Оплата прошла")));

        // Act
        TemplateImportResult result = importService.importTemplates(command);

        // Assert
        assertThat(result.created()).isEqualTo(2);
        assertThat(result.imported()).isEqualTo(3);
        assertThat(result.skipped()).isZero();
        assertThat(result.failures()).isEmpty();
        ArgumentCaptor<Template> saved = ArgumentCaptor.forClass(Template.class);
        verify(templates, times(2)).save(saved.capture());
        Template otp = saved.getAllValues().getFirst();
        assertThat(otp.versions()).hasSize(2);
        assertThat(otp.publishedVersions()).containsOnlyKeys(ContentLocale.RU, ContentLocale.UZ);
        assertThat(otp.publishedVersion(ContentLocale.RU).orElseThrow().reviewedBy())
                .contains("petrov");
    }

    @Test
    @DisplayName("FR-4.6: re-running the same file imports nothing and leaves the published wording alone")
    void importIsIdempotent() {
        // Arrange
        Template existing = template();
        existing.addVersion(publishedVersion(existing, "Код: {CODE}"));
        when(templates.findByCode(CODE)).thenReturn(Optional.of(existing));

        // Act
        TemplateImportResult result = importService.importTemplates(new ImportTemplatesCommand(
                REVIEWER, "legacy-import", "petrov", List.of(row("OTP_LOGIN", ContentLocale.RU, "Код: {CODE}"))));

        // Assert
        assertThat(result.created()).isZero();
        assertThat(result.imported()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(existing.versions()).hasSize(1);
    }

    @Test
    @DisplayName("FR-4.6: a row the domain refuses is reported and the rest of the file still goes in")
    void importReportsBadRowsAndContinues() {
        // Arrange
        Template existing = template();
        when(templates.findByCode(CODE)).thenReturn(Optional.of(existing));
        when(templates.findByCode(TemplateCode.of("PAYMENT_OK"))).thenReturn(Optional.empty());

        // Act — OTP_LOGIN is an SMS card, the file claims EMAIL
        TemplateImportResult result = importService.importTemplates(new ImportTemplatesCommand(
                REVIEWER,
                "legacy-import",
                null,
                List.of(
                        new ImportTemplatesCommand.Row(
                                "OTP_LOGIN", Channel.EMAIL, ContentLocale.RU, "Тема", "Текст", null, null),
                        row("PAYMENT_OK", ContentLocale.RU, "Оплата прошла"))));

        // Assert
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().getFirst().code()).isEqualTo("OTP_LOGIN");
        assertThat(result.failures().getFirst().reason()).contains("already exists on channel SMS");
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.imported()).isEqualTo(1);
    }

    @Test
    @DisplayName("FR-4.2: an import may not be approved by its own author")
    void importRefusesSelfApproval() {
        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> new ImportTemplatesCommand(
                        REVIEWER,
                        "legacy-import",
                        "legacy-import",
                        List.of(row("OTP_LOGIN", ContentLocale.RU, "Код: {CODE}"))));
    }

    private static ImportTemplatesCommand.Row row(String code, ContentLocale locale, String text) {
        return new ImportTemplatesCommand.Row(code, Channel.SMS, locale, null, text, "Чакана", "retail-team");
    }

    private static Template template() {
        return Template.create(TemplateId.newId(), CODE, Channel.SMS, "Чакана", "retail-team");
    }

    private static TemplateVersion draft(Template template, String text) {
        return TemplateVersion.draft(
                TemplateVersionId.newId(),
                template.id(),
                template.nextVersionNumber(ContentLocale.RU),
                ContentLocale.RU,
                TemplateVersion.Body.ofText(text),
                "ivanov");
    }

    private static TemplateVersion publishedVersion(Template template, String text) {
        TemplateVersion version = draft(template, text);
        version.submitForReview();
        version.publish("petrov", NOW);
        return version;
    }
}
