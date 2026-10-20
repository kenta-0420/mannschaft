package com.mannschaft.app.reservation.service;

import com.mannschaft.app.reservation.ReminderStatus;
import com.mannschaft.app.reservation.entity.ReservationReminderEntity;
import com.mannschaft.app.reservation.event.ReservationReminderDispatchEvent;
import com.mannschaft.app.reservation.repository.ReservationReminderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 予約リマインド実送信の「1 件ぶん」を実行する {@link Propagation#REQUIRES_NEW} 実行 Bean
 * （Issue #2997 / CMP-260827-1152 G3。金型: {@code ShiftPreferenceReminderRunner}）。
 *
 * <h2>是正前の欠陥</h2>
 * <p>是正前は {@code ReservationReminderDispatchBatchService#dispatchDueReminders} が全体を 1 トランザクションで包み、
 * ループ内で {@code NotificationHelper#notify}（既定 {@code REQUIRED} で業務TXに参加）を呼んで 1 件ずつ catch していた。
 * 1 件の DB 例外が rollback-only を残すため、catch して続行した<b>他のリマインダーの SENT 記録もコミット時にまとめて
 * 巻き戻り</b>、通知は送られているのに印が消えて次周回で二重送信される状態だった。</p>
 *
 * <h2>送信済みの印の契約（マスター裁可 M1: 配送の前に条件付き UPDATE で先取りする）</h2>
 * <ul>
 *   <li>{@link #publishOne} は<b>印を立てず</b>、配送要求の publish だけを行う。リマインダーは PENDING のまま。</li>
 *   <li>配送リスナーが配送の<b>前</b>に {@link #claim}（別の {@code REQUIRES_NEW}、条件付き UPDATE
 *       {@code SET status=SENT WHERE id=? AND status=PENDING}）で印を先取りする。更新が 0 件なら他の配送が担当済みなので
 *       何もしない。{@code @SchedulerLock} は publish までしか守らず非同期配送の完了までは守らないため、
 *       次周回や管理画面からの再実行で同じリマインダーが再 publish されても、配送は 1 回だけになる。</li>
 *   <li>配送が例外で失敗したときだけ {@link #release} で PENDING に戻す（次周回で再送される）。
 *       受信者は予約者 1 人なので「全員失敗」と同義。成功・deny は SENT のまま残す。</li>
 *   <li>戻す処理が失敗したら ERROR ログのみ（SENT が残るので再送されない）。</li>
 *   <li>event-pool が飽和して配送リスナーの投入が拒否された場合は claim されず PENDING のまま残り、
 *       次周回で再送される。</li>
 *   <li><b>既知の限界（M1）</b>: claim の後でプロセスが落ちると、そのリマインドは失われる（SENT のまま配送されない）。
 *       スキーマを増やさず重複配送を防ぐための引き換えであり、旧実装（通知と印が同一TX）にはなかった。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationReminderDispatchRunner {

    private final ReservationReminderRepository reminderRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 1 件のリマインドの配送要求を独立トランザクションで publish する（印は立てない）。
     *
     * <p>publish した配送要求は {@code AFTER_COMMIT} でのみ発火するため、
     * このトランザクションがロールバックすれば通知は作られない。</p>
     *
     * @param event 配送要求
     * @return publish した場合 {@code true}、対象外（既に PENDING でない・削除済み）なら {@code false}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean publishOne(ReservationReminderDispatchEvent event) {
        ReservationReminderEntity reminder = reminderRepository.findById(event.reminderId()).orElse(null);
        if (reminder == null || reminder.getStatus() != ReminderStatus.PENDING) {
            return false;
        }
        eventPublisher.publishEvent(event);
        return true;
    }

    /**
     * 配送の前に送信済みの印（SENT）を先取りする（配送リスナーから呼ぶ）。
     *
     * @param reminderId リマインダーID
     * @param claimedAt  先取り時刻（戻すときの照合キー。本番 DDL の sent_at は秒精度のため、秒に丸めて渡すこと。小数秒があると保存時に切り捨てられ release が一致しない）
     * @return 取得できた場合 {@code true}。他の配送が担当済み（または PENDING でない）なら {@code false}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(Long reminderId, LocalDateTime claimedAt) {
        return reminderRepository.claimPending(reminderId, claimedAt) == 1;
    }

    /**
     * 配送が失敗したときに先取りした印を PENDING に戻す（次周回で再送される）。
     *
     * <p>自分の claim（{@code sent_at = claimedAt}）だけを戻す。別の周回が取り直した印は消さない。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(Long reminderId, LocalDateTime claimedAt) {
        int updated = reminderRepository.releaseClaim(reminderId, claimedAt);
        if (updated == 0) {
            // 別の周回が取り直した、またはキャンセル済み等。再送は不要なのでエラー扱いにしない。
            log.info("予約リマインドの印は戻さなかった（別の周回が担当済み等）: reminderId={}", reminderId);
        }
    }
}
