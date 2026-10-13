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
 * <h2>送信済みの印の契約（AC-D・マスター裁可「配送後に印を立てる」＋重複配送の排他）</h2>
 * <p>印は {@code is_reminder_sent_48h} / {@code is_reminder_sent}。</p>
 * <ul>
 *   <li>{@link #remindOne}（項目TX）は<b>印を立てず</b>、配送要求の publish だけを行う
 *       （未提出者が 0 人のときだけは、通知するものが無いので旧挙動どおりその場で印を立てる）。</li>
 *   <li>配送リスナーは配送の<b>前</b>に {@link #claim}（別の {@code REQUIRES_NEW}、条件付き UPDATE
 *       {@code SET 印=true WHERE id=? AND 印=false}）で印を先取りする。更新が 0 件なら他の配送が担当済みなので何もしない。
 *       {@code @SchedulerLock} は publish までしか守らず、非同期配送の完了までは守らないため、
 *       次回 cron や管理画面からの再実行で同じスケジュールが再 publish されても、配送は 1 回だけになる。</li>
 *   <li>配送後に<b>全員が例外で失敗</b>したときだけ {@link #release} で印を戻す（次のバッチ実行で再送される）。
 *       1 人以上の成功・deny・一部失敗のときは立てたまま残す（再送しない）。</li>
 *   <li>戻す処理が失敗したら ERROR ログのみ（印が残るので再送されない）。</li>
 *   <li>publish 後に項目TXがロールバックすれば配送要求は発火せず、印も立たない。</li>
 *   <li><b>既知の限界</b>: 先取りの後でプロセスが落ちると、そのリマインドは失われる（印が立ったまま配送されない）。
 *       スキーマを増やさずに重複配送を防ぐための引き換えであり、旧実装（通知と印が同一TX）にはなかった。</li>
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
     * 配送の前に送信済みの印を先取りする（配送リスナーから呼ぶ）。
     *
     * @return 取得できた場合 {@code true}。他の配送が担当済み（または対象外）なら {@code false}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(Long scheduleId, ShiftPreferenceReminderKind kind) {
        return switch (kind) {
            case REMINDER_48H -> scheduleRepository.claimReminder48h(scheduleId) == 1;
            case REMINDER_24H -> scheduleRepository.claimReminder24h(scheduleId) == 1;
            default -> throw new IllegalArgumentException("定期バッチの対象外のリマインド種別: " + kind);
        };
    }

    /** 全員の配送が失敗したときに印を戻す（次回バッチで再送される）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(Long scheduleId, ShiftPreferenceReminderKind kind) {
        switch (kind) {
            case REMINDER_48H -> scheduleRepository.releaseReminder48h(scheduleId);
            case REMINDER_24H -> scheduleRepository.releaseReminder24h(scheduleId);
            default -> throw new IllegalArgumentException("定期バッチの対象外のリマインド種別: " + kind);
        }
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
