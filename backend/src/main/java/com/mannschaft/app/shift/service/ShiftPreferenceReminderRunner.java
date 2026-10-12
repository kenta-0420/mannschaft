package com.mannschaft.app.shift.service;

import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.event.ShiftPreferenceReminderKind;
import com.mannschaft.app.shift.event.ShiftPreferenceReminderNotificationEvent;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * シフト希望提出リマインドの「1 スケジュールぶん」を実行する {@link Propagation#REQUIRES_NEW} 実行 Bean
 * （Issue #2997 / CMP-260827-1152 G6。金型: {@code MemberInfoUpdateReminderRunner}・{@code ShiftSwapExpiryRunner}）。
 *
 * <h2>是正前の欠陥</h2>
 * <p>是正前は {@code ShiftPreferenceReminderBatchService#processReminders} に {@code @Transactional} が付き、
 * 全スケジュールの通知とフラグ更新を 1 トランザクションで包んだままループ内で 1 件ずつ catch していた。
 * 通知は業務TXに参加する（{@code REQUIRED}）ため、1 件の失敗が rollback-only を残し、
 * catch して続行した<b>他スケジュールの送信済みフラグもコミット時にまとめて巻き戻って</b>いた。</p>
 *
 * <h2>送信済みの印と再実行安全性（AC-D）</h2>
 * <p>印は {@code is_reminder_sent_48h} / {@code is_reminder_sent}。独立トランザクションの内側で
 * スケジュールを読み直し、<b>まだ COLLECTING で当該印が立っていなければ</b>印を立てて配送要求を publish する。
 * 印の保存に失敗すればこのトランザクションごとロールバックされ、配送要求は発火せず（{@code AFTER_COMMIT}）、
 * 印も立たないので次回バッチで再試行される。コミットされれば印は確定しており、配送の成否にかかわらず再実行で
 * 同じスケジュールが対象になることはない（成功済み受信者への再送もない）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShiftPreferenceReminderRunner {

    private final ShiftScheduleRepository scheduleRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 1 スケジュールぶんのリマインドを独立トランザクションで確定し、通知配送要求を publish する。
     *
     * <p>publish した通知配送要求は {@code AFTER_COMMIT} でのみ発火するため、
     * このトランザクションがロールバックすれば通知は 1 件も作られない。</p>
     *
     * @param scheduleId  スケジュールID
     * @param kind        {@link ShiftPreferenceReminderKind#REMINDER_48H} または {@link ShiftPreferenceReminderKind#REMINDER_24H}
     * @param unsubmitted 未提出メンバーID（抽出は呼び出し側で済ませる。空なら通知は発火せず印だけ立てる）
     * @return 印を立てた場合 {@code true}、対象外（既に印が立っている・COLLECTING でない・削除済み）なら {@code false}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean remindOne(Long scheduleId, ShiftPreferenceReminderKind kind, List<Long> unsubmitted) {
        ShiftScheduleEntity schedule = scheduleRepository.findById(scheduleId).orElse(null);
        if (schedule == null || schedule.getStatus() != ShiftScheduleStatus.COLLECTING) {
            return false;
        }
        switch (kind) {
            case REMINDER_48H -> {
                if (Boolean.TRUE.equals(schedule.getIsReminderSent48h())) {
                    return false;
                }
                schedule.markReminderSent48h();
            }
            case REMINDER_24H -> {
                if (Boolean.TRUE.equals(schedule.getIsReminderSent())) {
                    return false;
                }
                schedule.markReminderSent();
            }
            default -> throw new IllegalArgumentException("定期バッチの対象外のリマインド種別: " + kind);
        }
        scheduleRepository.save(schedule);

        if (unsubmitted != null && !unsubmitted.isEmpty()) {
            eventPublisher.publishEvent(new ShiftPreferenceReminderNotificationEvent(
                    kind, schedule.getId(), schedule.getTeamId(), schedule.getTitle(), List.copyOf(unsubmitted)));
        }
        return true;
    }
}
