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
 * <h2>送信済みの印の契約（AC-D・マスター裁可「配送後に印を立てる」）</h2>
 * <p>印は {@code is_reminder_sent_48h} / {@code is_reminder_sent}。</p>
 * <ul>
 *   <li>{@link #remindOne}（項目TX）は<b>印を立てず</b>、配送要求の publish だけを行う
 *       （未提出者が 0 人のときだけは、通知するものが無いので旧挙動どおりその場で印を立てる）。</li>
 *   <li>印は配送リスナーが {@code sendOne} を終えた後に {@link #markSent}（別の {@code REQUIRES_NEW}）で立てる。
 *       1 人以上に配送できた、または deny のときは立て、全員が例外で失敗したときは立てない
 *       （次のバッチ実行で再送される）。一部だけ失敗したときは立てる。</li>
 *   <li>publish 後に項目TXがロールバックすれば配送要求は発火せず、印も立たない。</li>
 *   <li>印の保存（{@link #markSent}）自体が失敗すると、配送済みなのに印が無く、次回に再送されうる。
 *       旧実装は印と通知が同一TXで一緒に巻き戻っていたため、この方向の重複は新たに生じうる（リスナーで ERROR ログ）。</li>
 * </ul>
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
     * @return 配送要求を publish した（または空で印を立てた）場合 {@code true}、
     *         対象外（既に印が立っている・COLLECTING でない・削除済み）なら {@code false}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean remindOne(Long scheduleId, ShiftPreferenceReminderKind kind, List<Long> unsubmitted) {
        ShiftScheduleEntity schedule = scheduleRepository.findById(scheduleId).orElse(null);
        if (schedule == null || schedule.getStatus() != ShiftScheduleStatus.COLLECTING) {
            return false;
        }
        if (isMarked(schedule, kind)) {
            return false;
        }
        if (unsubmitted == null || unsubmitted.isEmpty()) {
            // 通知するものが無い。旧挙動どおり、その場で印を立てる。
            mark(schedule, kind);
            scheduleRepository.save(schedule);
            return true;
        }
        // 印は立てない。配送リスナーが配送後に markSent で立てる。
        eventPublisher.publishEvent(new ShiftPreferenceReminderNotificationEvent(
                kind, schedule.getId(), schedule.getTeamId(), schedule.getTitle(), List.copyOf(unsubmitted)));
        return true;
    }

    /**
     * 配送後に送信済みの印を独立トランザクションで立てる（配送リスナーから呼ぶ）。
     *
     * @return 立てた場合 {@code true}、既に立っている・対象外なら {@code false}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markSent(Long scheduleId, ShiftPreferenceReminderKind kind) {
        ShiftScheduleEntity schedule = scheduleRepository.findById(scheduleId).orElse(null);
        if (schedule == null || isMarked(schedule, kind)) {
            return false;
        }
        mark(schedule, kind);
        scheduleRepository.save(schedule);
        return true;
    }

    private static boolean isMarked(ShiftScheduleEntity schedule, ShiftPreferenceReminderKind kind) {
        return switch (kind) {
            case REMINDER_48H -> Boolean.TRUE.equals(schedule.getIsReminderSent48h());
            case REMINDER_24H -> Boolean.TRUE.equals(schedule.getIsReminderSent());
            default -> throw new IllegalArgumentException("定期バッチの対象外のリマインド種別: " + kind);
        };
    }

    private static void mark(ShiftScheduleEntity schedule, ShiftPreferenceReminderKind kind) {
        if (kind == ShiftPreferenceReminderKind.REMINDER_48H) {
            schedule.markReminderSent48h();
        } else {
            schedule.markReminderSent();
        }
    }
}
