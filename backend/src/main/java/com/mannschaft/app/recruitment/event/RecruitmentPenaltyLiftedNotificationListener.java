package com.mannschaft.app.recruitment.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.NotificationType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.recruitment.PenaltyLiftReason;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.repository.RecruitmentPenaltySettingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentUserPenaltyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 期限切れで自動解除された募集ペナルティについて本人へ通知を配送する。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecruitmentPenaltyLiftedNotificationListener {

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final RecruitmentUserPenaltyRepository penaltyRepository;
    private final RecruitmentPenaltySettingRepository settingRepository;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "確定済みのペナルティ解除を本人へ知らせる通知であり、募集機能のgate状態にかかわらず配送する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPenaltyLifted(RecruitmentPenaltyLiftedNotificationEvent event) {
        if (event.penaltyId() == null || event.recipientUserId() == null
                || event.scopeType() == null || event.liftReason() == null) {
            log.warn("募集ペナルティ解除通知をスキップ: penaltyId={}, recipientUserId={}",
                    event.penaltyId(), event.recipientUserId());
            return;
        }

        RecruitmentScopeType sourceScopeType = event.scopeType();
        Long sourceScopeId = event.scopeId();
        if (sourceScopeType == RecruitmentScopeType.GLOBAL) {
            var penalty = penaltyRepository.findById(event.penaltyId()).orElse(null);
            var setting = penalty == null ? null
                    : settingRepository.findById(penalty.getTriggeredBySettingId()).orElse(null);
            if (setting == null) {
                log.warn("GLOBAL募集ペナルティの元スコープを取得できず解除通知をスキップ: penaltyId={}",
                        event.penaltyId());
                return;
            }
            sourceScopeType = setting.getScopeType();
            sourceScopeId = setting.getScopeId();
        }
        if (sourceScopeId == null) {
            log.warn("募集ペナルティ解除通知のスコープIDが不在: penaltyId={}", event.penaltyId());
            return;
        }

        String body = event.liftReason() == PenaltyLiftReason.AUTO_EXPIRED
                ? "ペナルティ #" + event.penaltyId() + "（"
                        + sourceScopeType.name() + " #" + sourceScopeId
                        + "）は、期限到来（AUTO_EXPIRED）により自動解除されました。"
                : "ペナルティ #" + event.penaltyId() + "（"
                        + sourceScopeType.name() + " #" + sourceScopeId
                        + "）は、再計算（" + event.liftReason().name() + "）により解除されました。";

        try {
            NotificationDeliveryResult result = notificationDeliveryRunner.sendOne(
                    new NotificationDeliveryRequest(
                            event.recipientUserId(), NotificationType.RECRUITMENT_PENALTY_LIFTED.name(),
                            NotificationPriority.NORMAL,
                            "募集ペナルティが解除されました",
                            body,
                            "RECRUITMENT_PENALTY", event.penaltyId(),
                            NotificationScopeType.valueOf(sourceScopeType.name()), sourceScopeId,
                            null, null));
            if (result == NotificationDeliveryResult.VISIBILITY_DENIED) {
                log.warn("募集ペナルティ解除通知がvisibility denyでスキップ: penaltyId={}, recipientUserId={}",
                        event.penaltyId(), event.recipientUserId());
            }
        } catch (Exception e) {
            log.error("募集ペナルティ解除通知の配送失敗: penaltyId={}, recipientUserId={}",
                    event.penaltyId(), event.recipientUserId(), e);
        }
    }
}
