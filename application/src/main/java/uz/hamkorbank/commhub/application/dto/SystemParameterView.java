package uz.hamkorbank.commhub.application.dto;

import java.time.Instant;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.support.Guard;

/** One system parameter as the administration screen shows it (§11.2 "Администрирование"). */
public record SystemParameterView(String key, String value, String description, Instant updatedAt, String updatedBy) {

    public SystemParameterView {
        Guard.notBlank(key, "SystemParameterView.key");
    }

    public Optional<String> descriptionOptional() {
        return Optional.ofNullable(description);
    }
}
