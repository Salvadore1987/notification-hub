package uz.hamkorbank.commhub.adapter.in.rest.mapper;

import java.time.Instant;
import org.mapstruct.Mapper;
import uz.hamkorbank.commhub.adapter.in.rest.dto.BatchAcceptedResponse;
import uz.hamkorbank.commhub.adapter.in.rest.dto.BatchActionResponse;
import uz.hamkorbank.commhub.adapter.in.rest.dto.BatchItemsResponse;
import uz.hamkorbank.commhub.adapter.in.rest.dto.BatchProgressResponse;
import uz.hamkorbank.commhub.adapter.in.rest.dto.BatchStatusResponse;
import uz.hamkorbank.commhub.adapter.in.rest.dto.DeliveryResponse;
import uz.hamkorbank.commhub.adapter.in.rest.dto.ItemRejectionResponse;
import uz.hamkorbank.commhub.adapter.in.rest.dto.MessageAcceptedResponse;
import uz.hamkorbank.commhub.adapter.in.rest.dto.MessageStatusResponse;
import uz.hamkorbank.commhub.adapter.in.rest.dto.TransitionResponse;
import uz.hamkorbank.commhub.application.dto.BatchAcceptedResult;
import uz.hamkorbank.commhub.application.dto.BatchControlResult;
import uz.hamkorbank.commhub.application.dto.BatchItemsResult;
import uz.hamkorbank.commhub.application.dto.BatchProgressDto;
import uz.hamkorbank.commhub.application.dto.BatchView;
import uz.hamkorbank.commhub.application.dto.MessageView;
import uz.hamkorbank.commhub.application.dto.SubmitMessageResult;
import uz.hamkorbank.commhub.domain.model.vo.Money;

/**
 * Application results → the response bodies of §8.2 (AR-06).
 *
 * <p>Nothing but conversion happens here, and every value object is flattened to the string form the
 * contract publishes: a source system integrates against {@code "messageId": "0192..."}, not against
 * whatever shape {@code MessageId} happens to have this release.
 */
@Mapper(componentModel = "spring")
public interface RestResponseMapper {

    default MessageAcceptedResponse toAccepted(SubmitMessageResult result) {
        return new MessageAcceptedResponse(
                result.messageIdOptional().map(Object::toString).orElse(null),
                result.status().name());
    }

    default MessageStatusResponse toStatus(MessageView view) {
        return new MessageStatusResponse(
                view.messageId().toString(),
                view.streamId().value(),
                view.externalMessageId().value(),
                view.batchId() == null ? null : view.batchId().toString(),
                view.status().name(),
                view.reasonOptional().map(Enum::name).orElse(null),
                toDelivery(view.delivery()),
                view.history().stream().map(RestResponseMapper::toTransition).toList());
    }

    default BatchAcceptedResponse toAccepted(BatchAcceptedResult result) {
        return new BatchAcceptedResponse(
                result.batchId().toString(), result.status().name(), result.total());
    }

    default BatchItemsResponse toItems(BatchItemsResult result) {
        return new BatchItemsResponse(
                result.batchId().toString(),
                result.accepted(),
                result.duplicates(),
                result.rejected(),
                result.rejections().stream()
                        .map(RestResponseMapper::toItemRejection)
                        .toList(),
                toProgress(result.progress()));
    }

    default BatchActionResponse toAction(BatchControlResult result) {
        return new BatchActionResponse(
                result.batchId().toString(), result.status().name(), toProgress(result.progress()));
    }

    default BatchStatusResponse toStatus(BatchView view) {
        return new BatchStatusResponse(
                view.batchId().toString(),
                view.streamId().value(),
                view.channel().name(),
                view.status().name(),
                view.total(),
                toProgress(view.progress()),
                view.createdAt().toString(),
                view.costEstimateOptional().map(Money::toString).orElse(null));
    }

    default BatchProgressResponse toProgress(BatchProgressDto progress) {
        return new BatchProgressResponse(
                progress.total(),
                progress.processed(),
                progress.sent(),
                progress.delivered(),
                progress.failed(),
                progress.completionPercent());
    }

    private static DeliveryResponse toDelivery(MessageView.Delivery delivery) {
        return new DeliveryResponse(
                delivery.channelOptional().map(Enum::name).orElse(null),
                delivery.providerOptional().map(Object::toString).orElse(null),
                delivery.segments(),
                delivery.costOptional().map(Money::toString).orElse(null),
                delivery.acceptedAt().toString(),
                delivery.terminalAtOptional().map(Instant::toString).orElse(null),
                delivery.correlationId().value(),
                delivery.test());
    }

    private static TransitionResponse toTransition(MessageView.Transition transition) {
        return new TransitionResponse(
                transition.status().name(),
                transition.reason() == null ? null : transition.reason().name(),
                transition.detail(),
                transition.occurredAt().toString());
    }

    private static ItemRejectionResponse toItemRejection(BatchItemsResult.ItemRejection rejection) {
        return new ItemRejectionResponse(
                rejection.externalMessageId().value(), rejection.reason().name(), rejection.detail());
    }
}
