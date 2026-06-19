package com.mannschaft.app.reservation.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationReminderEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationReminderRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 予約リマインド実送信バッチ（F03.4 段階拡張 ⑥）。
 *
 * <p>予約確定時に {@link ReservationReminderEventListener} が {@code reservation_reminders} へ
 * {@code remind_at} / {@code status = PENDING} で生成したリマインダーを、{@code remind_at} 到来後に
 * 拾って実際に通知送出する配線である。生成は実装済みだが送出が未配線（{@code processPendingReminders}
 * が PENDING→SENT にマークするだけで送らない）だったギャップを根治する。</p>
 *
 * <p><b>実行タイミング:</b> {@code @Scheduled(fixedDelay = 60_000)}（1 分間隔。設計書 §リマインドバッチに合致）。
 * 多重起動防止のため {@link SchedulerLock} を付ける（{@link EmergencyClosureReminderBatchService} の作法に倣う）。</p>
 *
 * <p><b>通知チャネル:</b> {@link NotificationHelper#notify} 経由（アプリ内通知 ＋ WebPush dispatch・チャネル非依存）。
 * 設計書 §F04.3 連携の通知タイプ {@code RESERVATION_REMINDER} を用いる。メールは設計書に無いため送らない。</p>
 *
 * <p><b>二重送信回避:</b> 送出に成功した行のみ {@code markSent()} で {@code SENT} ＋ {@code sentAt} を立てる
 * （送信 → SENT マークの順序を厳守）。送出失敗は {@code log.error} で正直に記録し、{@code SENT} に
 * しないでリトライ余地を残す（次回ポーリングで再送される）。一件の失敗が他の行の処理を巻き込まないよう
 * 行単位で try/catch する。{@link EmergencyClosureReminderBatchService} の作法に倣う。</p>
 *
 * <p><b>トランザクション境界:</b> 本クラスはバッチであり、確定 TX の AFTER_COMMIT 副作用である
 * {@code @TransactionalEventListener} とは別物。スケジューラ起点で新規 TX を開始する素の
 * {@code @Transactional}（=REQUIRED）でよい。</p>
 *
 * <p><b>受信者・枠情報の解決:</b> {@code reservation_reminders} は {@code reservationId} のみ保持するため、
 * reservation（受信者 {@code userId}・{@code teamId}・{@code reservationSlotId}）→ slot（タイトル・開始日時）
 * の順に辿って解決する。N+1 を避けるため reservation / slot を一括取得してからループ処理する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationReminderDispatchBatchService {

    /** 設計書 §F04.3 連携の通知タイプ。 */
    private static final String NOTIFICATION_TYPE = "RESERVATION_REMINDER";

    /** 通知 sourceType（F00 visibility / 受信権の判定キー）。 */
    private static final String SOURCE_TYPE = "RESERVATION";

    private static final DateTimeFormatter SLOT_AT_FORMAT =
            DateTimeFormatter.ofPattern("M月d日 HH:mm");

    private final ReservationReminderService reminderService;
    private final ReservationReminderRepository reminderRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationSlotRepository slotRepository;
    private final NotificationHelper notificationHelper;

    /**
     * {@code remind_at} が到来した PENDING リマインダーを拾って通知送出する。
     *
     * <p>送出成功で {@code SENT} ＋ {@code sentAt} をマークし二重送信を防ぐ。送出失敗は
     * 握りつぶさず {@code log.error} に記録し、{@code SENT} にせず次回再送の余地を残す。</p>
     */
    @BatchEndpoint(name = "reservation-reminder-dispatch",
            description = "予約リマインド（remind_at 到来済み PENDING）を 1 分毎に通知送出する")
    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "reservationReminderDispatchBatch", lockAtLeastFor = "30s", lockAtMostFor = "5m")
    @Transactional
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

        int sent = 0;
        for (ReservationReminderEntity reminder : dueReminders) {
            ReservationEntity reservation = reservationMap.get(reminder.getReservationId());
            if (reservation == null) {
                // 予約が論理削除された等で解決できない。送れないので SENT にせず記録のみ。
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
                sendReminder(reservation, slot);
                // 送信成功後に初めて SENT をマークする（送信 → マークの順序＝二重送信回避）。
                reminder.markSent();
                reminderRepository.save(reminder);
                sent++;
            } catch (Exception e) {
                // 送出失敗は握りつぶさない。SENT にしないことで次回ポーリングで再送される。
                log.error("予約リマインド送出失敗（次回再送）: reminderId={}, reservationId={}",
                        reminder.getId(), reservation.getId(), e);
            }
        }
        log.info("予約リマインド送出バッチ: 対象{}件中 {}件送出", dueReminders.size(), sent);
    }

    /**
     * 予約者本人へ予約リマインド通知を送出する。
     *
     * <p>{@link NotificationHelper#notify} の引数の決め方:</p>
     * <ul>
     *   <li>{@code userId} = 予約者本人（{@code reservation.userId}）</li>
     *   <li>{@code notificationType} = {@code RESERVATION_REMINDER}（設計書 §F04.3 連携）</li>
     *   <li>{@code sourceType / sourceId} = {@code RESERVATION} / 予約ID（F00 visibility・受信権の判定キー）</li>
     *   <li>{@code scopeType / scopeId} = {@code TEAM} / チームID</li>
     *   <li>{@code actionUrl} = 予約一覧（チームの予約画面・他リスナーと統一）</li>
     *   <li>{@code actorId} = {@code null}（システム発の自動リマインドで操作者は存在しない）</li>
     * </ul>
     */
    private void sendReminder(ReservationEntity reservation, ReservationSlotEntity slot) {
        LocalDateTime slotStartAt = slot.getSlotDate().atTime(slot.getStartTime());
        String slotAtStr = slotStartAt.format(SLOT_AT_FORMAT);
        String slotTitle = slot.getTitle() != null ? slot.getTitle() : "ご予約";

        String title = "予約リマインド";
        String body = String.format("%s に「%s」のご予約があります。", slotAtStr, slotTitle);
        String actionUrl = "/teams/" + reservation.getTeamId() + "/reservations";

        notificationHelper.notify(
                reservation.getUserId(),
                NOTIFICATION_TYPE,
                title,
                body,
                SOURCE_TYPE,
                reservation.getId(),
                NotificationScopeType.TEAM,
                reservation.getTeamId(),
                actionUrl,
                null
        );

        log.info("予約リマインド送出: reservationId={}, userId={}, slotStartAt={}",
                reservation.getId(), reservation.getUserId(), slotStartAt);
    }
}
