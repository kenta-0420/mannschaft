package com.mannschaft.app.notification.confirmable.event;

import com.mannschaft.app.common.SystemUsers;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationService;
import com.mannschaft.app.recruitment.event.RecruitmentPenaltyAppliedNotificationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.List;

/** 確定した募集ペナルティについて、本人へ緊急の確認通知を送る。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecruitmentPenaltyAppliedNotificationListener {

    static final String SOURCE_TYPE = "RECRUITMENT_PENALTY";

    private final ConfirmableNotificationService confirmableNotificationService;
    private final ConfirmableNotificationRepository confirmableNotificationRepository;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "確定したペナルティを本人へ知らせるため、募集機能のgate状態にかかわらず配送する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPenaltyApplied(RecruitmentPenaltyAppliedNotificationEvent event) {
        if (event.penaltyId() == null || event.recipientUserId() == null
                || event.sourceScopeType() == null || event.sourceScopeId() == null
                || event.expiresAt() == null) {
            log.warn("募集ペナルティ適用通知をスキップ: penaltyId={}, recipientUserId={}",
                    event.penaltyId(), event.recipientUserId());
            return;
        }

        try {
            if (confirmableNotificationRepository.existsBySourceTypeAndSourceId(SOURCE_TYPE, event.penaltyId())) {
                return;
            }
            confirmableNotificationService.sendFromSource(
                    SOURCE_TYPE,
                    event.penaltyId(),
                    ScopeType.valueOf(event.sourceScopeType().name()),
                    event.sourceScopeId(),
                    "無断キャンセルによる申込制限を確認してください",
                    "無断キャンセルの記録が設定された回数に達したため、募集への申込が制限されました。"
                            + "解除予定: " + event.expiresAt() + "。内容を確認してください。",
                    ConfirmableNotificationPriority.URGENT,
                    LocalDateTime.ofInstant(event.expiresAt(), UserZoneLocalDateTimeParser.SERVER_ZONE),
                    // 専用の「自分のペナルティ」表示画面は未実装（設計書 §12: 債務者向け一覧はスコープ外）。
                    // ペナルティは募集への申込を制限するものであり、制限は一覧/申込時に可視化される
                    // 募集一覧フィード（/recruitment-listings）へ遷移させる（特定の listingId は持たないため）。
                    "/recruitment-listings",
                    SystemUsers.SYSTEM_USER_ID,
                    List.of(event.recipientUserId()));
        } catch (Exception e) {
            log.error("募集ペナルティ適用通知の配送失敗: penaltyId={}, recipientUserId={}",
                    event.penaltyId(), event.recipientUserId(), e);
        }
    }
}
