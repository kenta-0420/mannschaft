package com.mannschaft.app.directmail.service;

import com.mannschaft.app.directmail.dto.SesNotificationRequest;
import com.mannschaft.app.directmail.entity.DirectMailLogEntity;
import com.mannschaft.app.directmail.entity.DirectMailRecipientEntity;
import com.mannschaft.app.directmail.repository.DirectMailLogRepository;
import com.mannschaft.app.directmail.repository.DirectMailRecipientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * SES Webhook サービス。バウンス・苦情・開封通知を処理する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SesWebhookService {

    private final DirectMailRecipientRepository recipientRepository;
    private final DirectMailLogRepository mailLogRepository;

    /**
     * SES通知を処理する。
     */
    @Transactional
    public void handleNotification(SesNotificationRequest request) {
        if ("SubscriptionConfirmation".equals(request.getType())) {
            log.info("SES SubscriptionConfirmation 受信: topicArn={}", request.getTopicArn());
            // TODO: subscribeURL にアクセスして確認
            return;
        }

        String messageId = request.getMessageId();
        if (messageId == null) {
            log.warn("SES通知にmessageIdが含まれていません");
            return;
        }

        Optional<DirectMailRecipientEntity> recipientOpt = recipientRepository.findBySesMessageId(messageId);
        if (recipientOpt.isEmpty()) {
            log.warn("SES通知の受信者が見つかりません: messageId={}", messageId);
            return;
        }

        DirectMailRecipientEntity recipient = recipientOpt.get();
        String notificationType = request.getNotificationType();

        if ("Bounce".equals(notificationType)) {
            recipient.markBounced(request.getBounceType());
            recipientRepository.save(recipient);

            // ログのバウンス数をインクリメント
            mailLogRepository.findById(recipient.getMailLogId()).ifPresent(mailLog -> {
                mailLog.incrementBouncedCount();
                mailLogRepository.save(mailLog);
            });

            log.info("SESバウンス処理: recipientId={}, bounceType={}", recipient.getId(), request.getBounceType());

        } else if ("Complaint".equals(notificationType)) {
            recipient.markComplained();
            recipientRepository.save(recipient);
            log.info("SES苦情処理: recipientId={}", recipient.getId());

        } else if ("Delivery".equals(notificationType)) {
            // 配信確認（特に処理なし）
            log.debug("SES配信確認: messageId={}", messageId);

        } else if ("Open".equals(notificationType)) {
            recipient.markOpened();
            recipientRepository.save(recipient);

            mailLogRepository.findById(recipient.getMailLogId()).ifPresent(mailLog -> {
                mailLog.incrementOpenedCount();
                mailLogRepository.save(mailLog);
            });

            log.info("SES開封記録: recipientId={}", recipient.getId());
        }
    }
}
