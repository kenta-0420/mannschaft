package com.mannschaft.app.skill.service;

import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.skill.NotificationType;
import com.mannschaft.app.skill.entity.MemberSkillEntity;
import com.mannschaft.app.skill.entity.SkillExpiryNotificationEntity;
import com.mannschaft.app.skill.event.SkillExpiryReminderEvent;
import com.mannschaft.app.skill.repository.MemberSkillQueryRepository;
import com.mannschaft.app.skill.repository.MemberSkillRepository;
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
 * 資格期限リマインダーバッチサービス。
 * 毎日8時（JST）に実行し、期限切れ前通知と自動ステータス更新を行う。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillExpiryReminderBatchService {

    private final MemberSkillQueryRepository memberSkillQueryRepository;
    private final MemberSkillRepository memberSkillRepository;
    private final SkillExpiryNotificationRepository notificationRepository;
    private final DomainEventPublisher eventPublisher;

    /**
     * 毎日8時（JST）に実行。
     * 1. 30日前リマインダー
     * 2. 7日前リマインダー
     * 3. 期限切れ自動ステータス更新（ACTIVE → EXPIRED）
     */
    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "skill_expiry_reminder", lockAtMostFor = "PT10M")
    @Transactional
    public void runReminder() {
        log.info("資格期限リマインダーバッチ開始");
        LocalDate today = LocalDate.now();

        int days30Count = processReminder(today.plusDays(30), NotificationType.DAYS_30, "DAYS_30");
        int days7Count = processReminder(today.plusDays(7), NotificationType.DAYS_7, "DAYS_7");
        int expiredCount = processExpiry();

        log.info("資格期限リマインダーバッチ完了: days30={}, days7={}, expired={}", days30Count, days7Count, expiredCount);
    }

    // ========================================
    // 内部メソッド
    // ========================================

    /**
     * 指定閾値以内に失効する資格に対してリマインダーを処理する。
     *
     * @param threshold        期限日の閾値
     * @param notificationType NotificationType Enum
     * @param typeStr          通知種別文字列（"DAYS_30" or "DAYS_7"）
     * @return 処理件数
     */
    private int processReminder(LocalDate threshold, NotificationType notificationType, String typeStr) {
        List<MemberSkillEntity> targets =
                memberSkillQueryRepository.findExpiringSoon(threshold, typeStr);

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

    /**
     * 有効期限が過ぎた ACTIVE 資格を EXPIRED に更新する。
     *
     * @return 更新件数
     */
    private int processExpiry() {
        LocalDate today = LocalDate.now();
        List<MemberSkillEntity> expiredSkills =
                memberSkillRepository.findByExpiresAtBeforeAndStatusAndDeletedAtIsNull(
                        today, com.mannschaft.app.skill.SkillStatus.ACTIVE);

        int count = 0;
        for (MemberSkillEntity skill : expiredSkills) {
            try {
                skill.expire();
                memberSkillRepository.save(skill);
                count++;
            } catch (Exception e) {
                log.warn("資格期限切れ更新失敗: memberSkillId={}", skill.getId(), e);
            }
        }
        return count;
    }
}
