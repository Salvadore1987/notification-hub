package uz.hamkorbank.commhub.adapter.out.persistence.json;

import uz.hamkorbank.commhub.domain.model.Tariff;

/** {@link Tariff} inside the {@code provider.tariff} column (FR-2.1, FR-6.2). */
public record TariffJson(MoneyJson perMessage, MoneyJson perSegment) {

    public static TariffJson of(Tariff tariff) {
        if (tariff == null) {
            return null;
        }
        return new TariffJson(MoneyJson.of(tariff.perMessage()), MoneyJson.of(tariff.perSegment()));
    }

    public Tariff toDomain() {
        return new Tariff(MoneyJson.toDomain(perMessage), MoneyJson.toDomain(perSegment));
    }

    public static Tariff toDomain(TariffJson json) {
        return json == null ? null : json.toDomain();
    }
}
