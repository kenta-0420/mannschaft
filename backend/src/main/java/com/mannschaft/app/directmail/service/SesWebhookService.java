package com.mannschaft.app.directmail.service;

import com.mannschaft.app.advertising.campaign.entity.AdEmailDelivery;
import com.mannschaft.app.advertising.campaign.enums.AdBounceType;
import com.mannschaft.app.advertising.campaign.repository.AdEmailDeliveryRepository;
import com.mannschaft.app.directmail.dto.SesNotificationRequest;
import com.mannschaft.app.directmail.entity.DirectMailRecipientEntity;
import com.mannschaft.app.directmail.repository.DirectMailLogRepository;
import com.mannschaft.app.directmail.repository.DirectMailRecipientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * SES 通知処理サービス。バウンス・苦情・開封通知を処理する。
 *
 * <p>F09.6 Phase 8a: 入口は HTTP webhook から SQS リスナー
 * （{@code directmail/listener/SesNotificationSqsListener}）へ移行した。
 * 本サービスの業務ロジックは入口非依存で、リスナーが SNS エンベロープを
 * パースして組み立てた {@link SesNotificationRequest} を受け取る。</p>
 *
 * <p>F09.17 Phase 11-b ε-C: SYSTEM_AD 送信メールへの bounce/complaint 通知を
 * {@code ad_email_deliveries.bounced_at / bounce_type} にも反映する。
 * direct_mail_recipient_id 経由で AdEmailDelivery を引き当て、存在する場合のみ更新する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SesWebhookService {

    private final DirectMailRecipientRepository recipientRepository;
    private final DirectMailLogRepository mailLogRepository;
    private final AdEmailDeliveryRepository adEmailDeliveryRepository;

    /**
     * SES通知を処理する。
     */
    @Transactional
    public void handleNotification(SesNotificationRequest request) {
        // F09.6 Phase 8a: SQS 経由では SNS SubscriptionConfirmation は発生しない
        // （HTTPS サブスクリプション特有の確認フロー）。SQS 購読の確立は terraform で行うため、
        // 旧 HTTP webhook 時代の confirmSubscription（SubscribeURL への GET ＝ SSRF 経路）は撤去した。
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

            // F09.17 ε-C: ad_email_deliveries 側にもバウンス反映
            reflectBounceToAdEmailDelivery(recipient.getId(), request.getBounceType());

            log.info("SESバウンス処理: recipientId={}, bounceType={}", recipient.getId(), request.getBounceType());

        } else if ("Complaint".equals(notificationType)) {
            recipient.markComplained();
            recipientRepository.save(recipient);

            // F09.17 ε-C: 苦情も ad_email_deliveries に COMPLAINT として反映 (HARD 同等扱い)
            reflectComplaintToAdEmailDelivery(recipient.getId());

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

    /**
     * F09.17 ε-C: SES バウンスを {@code ad_email_deliveries} に反映する。
     *
     * <p>recipient_id 経由で F09.17 由来配信履歴を引き当て、{@code bounced_at} と
     * {@code bounce_type} (HARD/SOFT) を更新する。F09.17 経路以外（通常の DirectMail）
     * では該当 row が存在しないため Optional.empty となり何もしない。</p>
     *
     * <p>SES の {@code bounceType} は通常 "Permanent"/"Transient"/"Undetermined" の三種。
     * "Permanent" → HARD、"Transient" → SOFT、その他 → HARD (安全側) として課金可否を決定する。</p>
     */
    private void reflectBounceToAdEmailDelivery(Long recipientId, String sesBounceType) {
        Optional<AdEmailDelivery> opt = adEmailDeliveryRepository.findByDirectMailRecipientId(recipientId);
        if (opt.isEmpty()) {
            // F09.17 由来でないメール (= 通常 DirectMail) は何もしない
            return;
        }
        AdEmailDelivery delivery = opt.get();
        AdBounceType bounceType = mapSesBounceType(sesBounceType);
        delivery.setBouncedAt(LocalDateTime.now());
        delivery.setBounceType(bounceType);
        adEmailDeliveryRepository.save(delivery);
        log.info("F09.17 ad_email_deliveries バウンス反映: deliveryId={} recipientId={} bounceType={}",
                delivery.getId(), recipientId, bounceType);
    }

    /**
     * F09.17 ε-C: SES 苦情通知を {@code ad_email_deliveries} に COMPLAINT として反映する。
     * COMPLAINT は HARD 同等扱い (設計書 §11 解決事項 8) で課金対象外。
     */
    private void reflectComplaintToAdEmailDelivery(Long recipientId) {
        Optional<AdEmailDelivery> opt = adEmailDeliveryRepository.findByDirectMailRecipientId(recipientId);
        if (opt.isEmpty()) {
            return;
        }
        AdEmailDelivery delivery = opt.get();
        delivery.setBouncedAt(LocalDateTime.now());
        delivery.setBounceType(AdBounceType.COMPLAINT);
        adEmailDeliveryRepository.save(delivery);
        log.info("F09.17 ad_email_deliveries 苦情反映: deliveryId={} recipientId={}",
                delivery.getId(), recipientId);
    }

    /**
     * SES の bounceType 文字列を {@link AdBounceType} に変換する。
     */
    static AdBounceType mapSesBounceType(String sesBounceType) {
        if (sesBounceType == null) {
            return AdBounceType.HARD;
        }
        return switch (sesBounceType) {
            case "Permanent" -> AdBounceType.HARD;
            case "Transient" -> AdBounceType.SOFT;
            default -> AdBounceType.HARD;
        };
    }
}
