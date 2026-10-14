package com.mannschaft.app.reservation.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationReminderEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.event.ReservationReminderDispatchEvent;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 予約リマインド実送信バッチ（F03.4 段階拡張 ⑥ / Issue #2997 G3 で通知を業務TXから切り離し）。
 *
 * <p>予約確定時に {@link com.mannschaft.app.reservation.event.ReservationReminderEventListener} が
 * {@code reservation_reminders} へ {@code remind_at} / {@code status = PENDING} で生成したリマインダーを、
 * {@code remind_at} 到来後に拾って実際に通知送出する配線である。</p>
 *
 * <p><b>実行タイミング:</b> {@code @Scheduled(fixedDelay = 60_000)}（1 分間隔。設計書 §リマインドバッチに合致）。
 * 多重起動防止のため {@link SchedulerLock} を付ける（{@link EmergencyClosureReminderBatchService} の作法に倣う）。</p>
 *
 * <h2>トランザクション境界（是正後）</h2>
 * <p>本クラスは<b>トランザクションを持たない非 TX オーケストレータ</b>である（是正前は全体を 1 つの {@code @Transactional} で包み、
 * 通知を業務TX内で送っていたため、1 件の失敗が他のリマインダーの SENT 記録まで巻き戻していた）。
 * 1 件ごとの処理は {@link ReservationReminderDispatchRunner#publishOne}（{@code REQUIRES_NEW}・別 Bean）に委譲し、
 * そこでは配送要求の publish だけを行う。実通知は
 * {@link com.mannschaft.app.reservation.event.ReservationReminderDispatchNotificationListener} が AFTER_COMMIT で行い、
 * 送信済みの印（SENT）はそのリスナーが配送の前に先取りする。契約の詳細は {@link ReservationReminderDispatchRunner}。</p>
 *
 * <p><b>通知チャネル:</b> アプリ内通知 ＋ WebPush dispatch（チャネル非依存）。
 * 設計書 §F04.3 連携の通知タイプ {@code RESERVATION_REMINDER} を用いる。メールは設計書に無いため送らない。</p>
 *
 * <p><b>受信者・枠情報の解決:</b> {@code reservation_reminders} は {@code reservationId} のみ保持するため、
 * reservation（受信者 {@code userId}・{@code teamId}・{@code reservationSlotId}）→ slot（タイトル・開始日時）
 * の順に辿って解決する。N+1 を避けるため reservation / slot を一括取得してからループ処理する。
 * 解決できない行（予約が論理削除された等）は PENDING のままスキップする（送れないので印を立てない）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationReminderDispatchBatchService {

    private final ReservationReminderService reminderService;
    private final ReservationRepository reservationRepository;
    private final ReservationSlotRepository slotRepository;
    private final ReservationReminderDispatchRunner reminderRunner;

    /**
     * {@code remind_at} が到来した PENDING リマインダーを拾って配送要求を publish する。
     *
     * <p>1 件の失敗（項目TXのロールバック・event-pool の投入拒否等）は ERROR ログに記録して次の行へ進み、
     * その行は PENDING のまま残って次周回で再送される。</p>
     */
    @BatchEndpoint(name = "reservation-reminder-dispatch",
            description = "予約リマインド（remind_at 到来済み PENDING）を 1 分毎に通知送出する")
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。予約リマインドの配信。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "reservationReminderDispatchBatch", lockAtLeastFor = "30s", lockAtMostFor = "5m")
    public void dispatchDueReminders() {
        // remind_at 到来済み PENDING の取得は Service 経由（Clock は Service が保持・直書き禁止）。
        List<ReservationReminderEntity> dueReminders = reminderService.findDueReminders();
        if (dueReminders.isEmpty()) {
            return;
        }

        // N+1 対策: reservation / slot を一括取得してからループ処理する。
        Set<Long> reservationIds = dueReminders.stream()
                .map(ReservationReminderEntity::getReservationId)
                .collect(Collectors.toSet());
        Map<Long, ReservationEntity> reservationMap = reservationRepository.findAllById(reservationIds)
                .stream()
                .collect(Collectors.toMap(ReservationEntity::getId, r -> r));
        Set<Long> slotIds = reservationMap.values().stream()
                .map(ReservationEntity::getReservationSlotId)
                .collect(Collectors.toSet());
        Map<Long, ReservationSlotEntity> slotMap = slotRepository.findAllById(slotIds)
                .stream()
                .collect(Collectors.toMap(ReservationSlotEntity::getId, s -> s));

        int published = 0;
        for (ReservationReminderEntity reminder : dueReminders) {
            ReservationEntity reservation = reservationMap.get(reminder.getReservationId());
            if (reservation == null) {
                // 予約が論理削除された等で解決できない。送れないので PENDING のまま記録のみ。
                log.warn("予約リマインド送出スキップ: 予約が解決できません reminderId={}, reservationId={}",
                        reminder.getId(), reminder.getReservationId());
                continue;
            }
            ReservationSlotEntity slot = slotMap.get(reservation.getReservationSlotId());
            if (slot == null) {
                log.warn("予約リマインド送出スキップ: 枠が解決できません reminderId={}, reservationId={}, slotId={}",
                        reminder.getId(), reservation.getId(), reservation.getReservationSlotId());
                continue;
            }
            try {
                if (reminderRunner.publishOne(new ReservationReminderDispatchEvent(
                        reminder.getId(),
                        reservation.getId(),
                        reservation.getTeamId(),
                        reservation.getUserId(),
                        slot.getSlotDate().atTime(slot.getStartTime()),
                        slot.getTitle()))) {
                    published++;
                }
            } catch (Exception e) {
                // 項目TXのロールバック・event-pool 投入拒否等。印は立っていない（PENDING のまま）ので次周回で再送される。
                // 他のリマインダーの処理には影響しない。
                log.error("予約リマインド送出失敗（次回再送）: reminderId={}, reservationId={}",
                        reminder.getId(), reservation.getId(), e);
            }
        }
        log.info("予約リマインド送出バッチ: 対象{}件中 {}件の配送要求を発行", dueReminders.size(), published);
    }
}
