package uz.hamkorbank.commhub.application.port.out.provider;

import uz.hamkorbank.commhub.domain.model.content.EmailContent;
import uz.hamkorbank.commhub.domain.model.vo.EmailAddress;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * One email handed to the SMTP adapter (EM-01).
 *
 * <p>The adapter builds {@code multipart/alternative} when the content carries both bodies and stamps
 * {@code X-Comm-Message-Id} with {@link #messageId()} for end-to-end identification (EM-01).
 */
public record EmailSubmission(
        ProviderRef provider,
        MessageId messageId,
        EmailAddress recipient,
        EmailContent content,
        SubmissionContext context) {

    public EmailSubmission {
        Guard.notNull(provider, "EmailSubmission.provider");
        Guard.notNull(messageId, "EmailSubmission.messageId");
        Guard.notNull(recipient, "EmailSubmission.recipient");
        Guard.notNull(content, "EmailSubmission.content");
        Guard.notNull(context, "EmailSubmission.context");
    }
}
