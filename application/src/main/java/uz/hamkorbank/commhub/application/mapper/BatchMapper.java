package uz.hamkorbank.commhub.application.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import uz.hamkorbank.commhub.application.dto.BatchAcceptedResult;
import uz.hamkorbank.commhub.application.dto.BatchControlResult;
import uz.hamkorbank.commhub.application.dto.BatchProgressDto;
import uz.hamkorbank.commhub.domain.model.Batch;

/** Conversions of the {@code Batch} aggregate into the batch DTOs (FR-1.6, FR-3.1, FR-3.2). */
@Mapper(componentModel = "spring")
public interface BatchMapper {

    /** Progress snapshot; the completion share is computed by the domain (FR-3.1). */
    @Mapping(target = "completionPercent", expression = "java(progress.completionPercent())")
    BatchProgressDto toProgressDto(Batch.Progress progress);

    default BatchAcceptedResult toAcceptedResult(Batch batch) {
        return new BatchAcceptedResult(batch.id(), batch.status(), batch.total());
    }

    default BatchControlResult toControlResult(Batch batch) {
        return new BatchControlResult(batch.id(), batch.status(), toProgressDto(batch.progress()));
    }
}
