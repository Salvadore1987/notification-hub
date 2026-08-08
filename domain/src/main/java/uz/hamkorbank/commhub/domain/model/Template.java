package uz.hamkorbank.commhub.domain.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;
import uz.hamkorbank.commhub.domain.model.type.TemplateStatus;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;
import uz.hamkorbank.commhub.domain.model.vo.TemplateId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * A notification template with its localised versions (§6.1, FR-4.1…FR-4.6).
 *
 * <p>Aggregate root over {@link TemplateVersion}: sending always resolves the published version for the
 * requested locale (FR-4.1). {@link ProviderMapping} keeps the correspondence with templates registered
 * on the provider side, e.g. the Playmobile {@code template-id}, together with its approval state
 * (FR-4.5).
 */
public final class Template extends AggregateRoot<TemplateId> {

    public static final int MAX_DIRECTION_LENGTH = 64;
    public static final int MAX_OWNER_LENGTH = 128;

    private final TemplateCode code;
    private final Channel channel;
    private final List<TemplateVersion> versions = new ArrayList<>();
    private final Map<ProviderCode, ProviderMapping> providerMappings = new LinkedHashMap<>();

    private String direction;
    private String owner;

    private Template(TemplateId id, TemplateCode code, Channel channel, String direction, String owner) {
        super(id);
        this.code = Guard.notNull(code, "Template.code");
        this.channel = Guard.notNull(channel, "Template.channel");
        this.direction = Guard.maxLength(direction, MAX_DIRECTION_LENGTH, "Template.direction");
        this.owner = Guard.maxLength(owner, MAX_OWNER_LENGTH, "Template.owner");
    }

    /**
     * Creates a template.
     *
     * @param direction business direction of the Bank: МСБ, Чакана, Ундирув … (§18.4, FR-4.6)
     * @param owner owning unit or user
     */
    public static Template create(TemplateId id, TemplateCode code, Channel channel, String direction, String owner) {
        return new Template(id, code, channel, direction, owner);
    }

    /** Adds a version; {@code (locale, version)} must be unique inside the template (FR-4.1). */
    public void addVersion(TemplateVersion version) {
        Guard.notNull(version, "version");
        Guard.isTrue(version.templateId().equals(id()), "template version belongs to another template");
        Guard.isTrue(
                versions.stream()
                        .noneMatch(existing ->
                                existing.locale() == version.locale() && existing.version() == version.version()),
                "version %d for locale %s already exists".formatted(version.version(), version.locale()));
        versions.add(version);
    }

    /** Number the next version of a locale should get (FR-4.1). */
    public int nextVersionNumber(ContentLocale locale) {
        Guard.notNull(locale, "locale");
        return versions.stream()
                        .filter(version -> version.locale() == locale)
                        .mapToInt(TemplateVersion::version)
                        .max()
                        .orElse(0)
                + 1;
    }

    /** The version a message may be rendered from: the published one for the locale (FR-4.1). */
    public Optional<TemplateVersion> publishedVersion(ContentLocale locale) {
        Guard.notNull(locale, "locale");
        return versions.stream()
                .filter(version -> version.locale() == locale && version.status() == TemplateStatus.PUBLISHED)
                .max(Comparator.comparingInt(TemplateVersion::version));
    }

    /** Latest version of a locale regardless of its status (admin panel view). */
    public Optional<TemplateVersion> latestVersion(ContentLocale locale) {
        Guard.notNull(locale, "locale");
        return versions.stream()
                .filter(version -> version.locale() == locale)
                .max(Comparator.comparingInt(TemplateVersion::version));
    }

    /** Locales that currently have a published version (FR-4.1). */
    public Map<ContentLocale, TemplateVersion> publishedVersions() {
        Map<ContentLocale, TemplateVersion> published = new EnumMap<>(ContentLocale.class);
        for (ContentLocale locale : ContentLocale.values()) {
            publishedVersion(locale).ifPresent(version -> published.put(locale, version));
        }
        return Collections.unmodifiableMap(published);
    }

    /** Registers or replaces the mapping onto a provider-side template (FR-4.5). */
    public void mapToProviderTemplate(ProviderMapping mapping) {
        Guard.notNull(mapping, "mapping");
        providerMappings.put(mapping.providerCode(), mapping);
    }

    public Optional<ProviderMapping> providerMapping(ProviderCode providerCode) {
        return Optional.ofNullable(providerMappings.get(providerCode));
    }

    public void updateDirection(String newDirection) {
        this.direction = Guard.maxLength(newDirection, MAX_DIRECTION_LENGTH, "Template.direction");
    }

    public void updateOwner(String newOwner) {
        this.owner = Guard.maxLength(newOwner, MAX_OWNER_LENGTH, "Template.owner");
    }

    public TemplateCode code() {
        return code;
    }

    public Channel channel() {
        return channel;
    }

    public Optional<String> direction() {
        return Optional.ofNullable(direction);
    }

    public Optional<String> owner() {
        return Optional.ofNullable(owner);
    }

    public List<TemplateVersion> versions() {
        return Collections.unmodifiableList(versions);
    }

    public Map<ProviderCode, ProviderMapping> providerMappings() {
        return Collections.unmodifiableMap(providerMappings);
    }

    /**
     * Correspondence with a template registered on the provider side (FR-4.5).
     *
     * <p>Approval with the operator/provider is an organisational process outside the Hub; the Hub only
     * stores its state.
     *
     * @param providerTemplateId provider-side identifier, e.g. Playmobile {@code template-id}
     * @param approved whether the provider has approved the template
     */
    public record ProviderMapping(ProviderCode providerCode, String providerTemplateId, boolean approved) {

        public static final int MAX_PROVIDER_TEMPLATE_ID_LENGTH = 64;

        public ProviderMapping {
            Guard.notNull(providerCode, "ProviderMapping.providerCode");
            Guard.notBlank(providerTemplateId, "ProviderMapping.providerTemplateId");
            Guard.maxLength(providerTemplateId, MAX_PROVIDER_TEMPLATE_ID_LENGTH, "ProviderMapping.providerTemplateId");
        }

        public static ProviderMapping pendingApproval(ProviderCode providerCode, String providerTemplateId) {
            return new ProviderMapping(providerCode, providerTemplateId, false);
        }

        public ProviderMapping approve() {
            return new ProviderMapping(providerCode, providerTemplateId, true);
        }
    }
}
