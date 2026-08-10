package uz.hamkorbank.commhub.adapter.out.provider.smtp;

import java.util.List;
import uz.hamkorbank.commhub.application.port.out.provider.EmailSubmission;
import uz.hamkorbank.commhub.application.port.out.provider.SubmissionContext;
import uz.hamkorbank.commhub.domain.model.content.Attachment;
import uz.hamkorbank.commhub.domain.model.content.EmailContent;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.Priority;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.CorrelationId;
import uz.hamkorbank.commhub.domain.model.vo.EmailAddress;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;

/** Fixtures shared by the email tests: one submission, varied by what its content carries. */
final class EmailSubmissions {

    static final String RECIPIENT = "client@example.com";

    private EmailSubmissions() {}

    static ProviderRef provider() {
        return new ProviderRef(
                ProviderId.newId(),
                ProviderCode.of("SMTP"),
                Channel.EMAIL,
                AdapterType.of(SmtpEmailAdapter.ADAPTER_TYPE));
    }

    static EmailSubmission textOnly(MessageId messageId) {
        return of(messageId, EmailContent.ofText("Выписка", "Ваша выписка готова."));
    }

    static EmailSubmission multipart(MessageId messageId) {
        return of(messageId, EmailContent.ofHtml("Выписка", "<p>Ваша выписка готова.</p>", "Ваша выписка готова."));
    }

    static EmailSubmission withAttachment(MessageId messageId, Attachment attachment) {
        return of(messageId, new EmailContent("Выписка", null, "Во вложении.", List.of(attachment), null));
    }

    static EmailSubmission of(MessageId messageId, EmailContent content) {
        return new EmailSubmission(
                provider(),
                messageId,
                EmailAddress.of(RECIPIENT),
                content,
                new SubmissionContext(TrafficClass.TRANSACTIONAL, Priority.NORMAL, CorrelationId.newId(), false));
    }
}
