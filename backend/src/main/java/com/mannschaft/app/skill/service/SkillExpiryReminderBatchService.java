package com.mannschaft.app.skill.service;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.skill.NotificationType;
import com.mannschaft.app.skill.entity.MemberSkillEntity;
import com.mannschaft.app.skill.entity.SkillExpiryNotificationEntity;
import com.mannschaft.app.skill.event.SkillExpiryReminderEvent;
import com.mannschaft.app.skill.repository.MemberSkillQueryRepository;
import com.mannschaft.app.skill.repository.SkillExpiryNotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 資格期限リマインダーバッチサービス。毎日8時（JST）に期限切れ前通知を送る。
 *
 * <p><b>失効ステータス更新（ACTIVE → EXPIRED）は本クラスの責務ではない。</b>
 * 通知は「機能が閉じていれば送る意味が無い」ので停止してよいが、
 * 失効更新を止めると期限切れの資格が ACTIVE のまま残り既存データの整合性が壊れる。
 * 判定が正反対であり、番人の禁止域はクラス単位で照合するため、
 * Gate 基盤工事④-B 第三陣で {@link SkillExpiryStatusUpdateBatchService} へ分離した。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillExpiryReminderBatchService {

    private final MemberSkillQueryRepository memberSkillQueryRepository;
    private final SkillExpiryNotificationRepository notificationRepository;
    private final DomainEventPublisher eventPublisher;

    /**
     * 毎日8時（JST）に実行。30日前・7日前リマインダーを送る。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
            gateKeys = "FEATURE_SKILL_RESUME_ENABLED",
            reason = "止まるのは期限前の通知送信のみで DB の資格データは書き換えない。資格・履歴書機能を閉じている間は通知を受け取る画面も閉じており、送り損ねた回を再開後に遡って送らないことは仕様として正しい（失効ステータス更新は SkillExpiryStatusUpdateBatchService が ALWAYS で担う）")
    @BatchEndpoint(name = "skill-expiry-reminder-daily",
            description = "資格期限の 30/7 日前リマインドを毎日 08:00 に送信する（自動失効は skill-expiry-status-update-daily）")
    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "skill_expiry_reminder", lockAtMostFor = "PT10M")
    @Transactional
    public void runReminder() {
        log.info("資格期限リマインダーバッチ開始");
        LocalDate today = LocalDate.now();

        int days30Count = processReminder(today, today.plusDays(30), NotificationType.DAYS_30, "DAYS_30");
        int days7Count = processReminder(today, today.plusDays(7), NotificationType.DAYS_7, "DAYS_7");

        log.info("資格期限リマインダーバッチ完了: days30={}, days7={}", days30Count, days7Count);
    }

    // ========================================
    // 内部メソッド
    // ========================================

    /**
     * 指定閾値以内に失効する資格に対してリマインダーを処理する。
     *
     * <p>{@code today} を渡して<b>既に失効した資格を除外</b>する。
     * 下限が無いと、停止や障害で数日走らなかった後の再開時に
     * 「期限まで30日です」を失効済みの資格へ送ってしまう。</p>
     *
     * @param today            基準日（これより前に失効済みのものは通知しない）
     * @param threshold        期限日の閾値
     * @param notificationType NotificationType Enum
     * @param typeStr          通知種別文字列（"DAYS_30" or "DAYS_7"）
     * @return 処理件数
     */
    private int processReminder(
            LocalDate today, LocalDate threshold, NotificationType notificationType, String typeStr) {
        List<MemberSkillEntity> targets =
                memberSkillQueryRepository.findExpiringSoon(today, threshold, typeStr);

        int count = 0;
        for (MemberSkillEntity skill : targets) {
            try {
                // イベント発行
                eventPublisher.publish(new SkillExpiryReminderEvent(
                        skill.getId(),
                        skill.getUserId(),
                        skill.getName(),
                        skill.getExpiresAt(),
                        typeStr));

                // 通知送信履歴をINSERT（冪等性保証）
                SkillExpiryNotificationEntity notification = SkillExpiryNotificationEntity.builder()
                        .memberSkillId(skill.getId())
                        .notificationType(notificationType)
                        .sentAt(LocalDateTime.now())
                        .build();
                notificationRepository.save(notification);

                count++;
            } catch (Exception e) {
                log.warn("資格期限リマインダー処理失敗: memberSkillId={}, type={}",
                        skill.getId(), typeStr, e);
            }
        }
        return count;
    }

}
