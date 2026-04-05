package com.mannschaft.app.directmail.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.directmail.entity.DirectMailLogEntity;
import com.mannschaft.app.directmail.entity.DirectMailRecipientEntity;
import com.mannschaft.app.directmail.repository.DirectMailLogRepository;
import com.mannschaft.app.directmail.repository.DirectMailRecipientRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;

import java.util.List;

/**
 * ダイレクトメール送信イベントリスナー。
 * トランザクションコミット後に非同期でSES送信を実行する。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DirectMailSendEventListener {

    private static final String FROM_ADDRESS = "noreply@mannschaft.app";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SesV2Client sesV2Client;
    private final DirectMailLogRepository mailLogRepository;
    private final DirectMailRecipientRepository recipientRepository;
    private final UserRoleRepository userRoleRepository;

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleDirectMailSend(DirectMailSendEvent event) {
        log.info("SES送信開始: mailLogId={}", event.mailLogId());

        DirectMailLogEntity mailLog = mailLogRepository.findById(event.mailLogId()).orElse(null);
        if (mailLog == null) {
            log.warn("メールログが見つかりません: mailLogId={}", event.mailLogId());
            return;
        }

        try {
            // recipientType/recipientFilter に基づいて対象メンバーを取得
            List<Object[]> recipients = resolveRecipients(
                    event.scopeType(), event.scopeId(),
                    mailLog.getRecipientType(), mailLog.getRecipientFilter());

            int sentCount = 0;
            for (Object[] row : recipients) {
                Long userId = ((Number) row[0]).longValue();
                String email = (String) row[1];

                DirectMailRecipientEntity recipient = DirectMailRecipientEntity.builder()
                        .mailLogId(mailLog.getId())
                        .userId(userId)
                        .email(email)
                        .build();

                try {
                    SendEmailResponse response = sesV2Client.sendEmail(SendEmailRequest.builder()
                            .fromEmailAddress(FROM_ADDRESS)
                            .destination(Destination.builder().toAddresses(email).build())
                            .content(EmailContent.builder()
                                    .simple(Message.builder()
                                            .subject(Content.builder().data(mailLog.getSubject()).build())
                                            .body(Body.builder()
                                                    .html(Content.builder().data(mailLog.getBodyHtml()).build())
                                                    .build())
                                            .build())
                                    .build())
                            .build());

                    recipient.markSent(response.messageId());
                    sentCount++;
                } catch (Exception e) {
                    log.warn("SES送信失敗: email={}", email, e);
                }
                recipientRepository.save(recipient);
            }

            mailLog.markSent(recipients.size(), sentCount);
            mailLogRepository.save(mailLog);
            log.info("SES送信完了: mailLogId={}, total={}, sent={}", event.mailLogId(), recipients.size(), sentCount);

        } catch (Exception e) {
            log.error("SES送信処理失敗: mailLogId={}", event.mailLogId(), e);
            mailLog.markFailed(e.getMessage());
            mailLogRepository.save(mailLog);
        }
    }

    /**
     * recipientType/recipientFilter に基づいて送信対象のユーザーID・メールペアを解決する。
     */
    private List<Object[]> resolveRecipients(String scopeType, Long scopeId,
                                              String recipientType, String recipientFilter) {
        if ("ROLE".equals(recipientType) && recipientFilter != null) {
            String roleName = extractRoleFromFilter(recipientFilter);
            if (roleName != null) {
                return userRoleRepository.findUserIdAndEmailByScopeAndRole(scopeType, scopeId, roleName);
            }
        }
        return userRoleRepository.findUserIdAndEmailByScope(scopeType, scopeId);
    }

    private String extractRoleFromFilter(String recipientFilter) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(recipientFilter);
            JsonNode roleNode = node.get("role");
            return roleNode != null ? roleNode.asText() : null;
        } catch (Exception e) {
            log.warn("recipientFilter のパース失敗: {}", recipientFilter, e);
            return null;
        }
    }
}
