package com.mannschaft.app.reservation.event;

import com.mannschaft.app.reservation.entity.ReservationPolicyEntity;
import com.mannschaft.app.reservation.service.ReservationPolicyService;
import com.mannschaft.app.reservation.service.ReservationReminderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 予約確定時のリマインダー自動生成リスナー（F03.4 ⑥ CONFIRMED 時リマインド自動生成）。
 *
 * <p>{@link ReservationConfirmedEvent} を購読し、チームの予約ポリシー
 * （{@code reservation_policies.remind_before_hours}・既定 {@code "24,1"}）に従って
 * スロット開始時刻から逆算したリマインド時刻を {@code reservation_reminders} に生成する。
 * 予約確定が実際に起きた経路（AUTO 自動確定／手動承認成功）でのみ発行されるイベントを受ける。</p>
 *
 * <p><b>トランザクション設計（最重要）:</b> 本ハンドラは DB 書き込みを伴うため
 * {@code @TransactionalEventListener(AFTER_COMMIT)} ＋ {@code @Transactional(REQUIRES_NEW)} を必ず付ける。
 * AFTER_COMMIT は確定 TX のコミット後に発火するため、その時点では新しいトランザクションが無く、
 * 素の {@code @Transactional}（=REQUIRED）を付けると起動時バリデーション（{@code TransactionalEventListenerFactory}
 * の登録）で ApplicationContext がロード不能になり全 SpringBootTest が巻き添えで落ちる
 * （既知の重大地雷: feedback_transactional_event_listener_requires_new）。
 * そのため新規トランザクションを開始する {@code REQUIRES_NEW} を必須とする。</p>
 *
 * <p><b>本リスナーは {@link ReservationAdminNotificationEventListener}（管理者通知）とは別クラス・別 Bean</b>
 * として定義し、同名 Bean / 同名ハンドラの衝突を回避する。</p>
 *
 * <p>リマインド生成は AFTER_COMMIT の副作用であり、ここでの失敗を既にコミット済みの確定 TX へ
 * 波及させてはならない。生成失敗は握りつぶさず {@code log.error} に正直に記録する
 * （AFTER_COMMIT 副作用失敗の正規パターン。{@link ReservationAdminNotificationEventListener} の作法に倣う）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationReminderEventListener {

    private final ReservationPolicyService policyService;
    private final ReservationReminderService reminderService;
    private final Clock clock;

    /**
     * 予約確定イベントを受信し、ポリシーに従ってリマインダーを自動生成する。
     *
     * <p>{@code remind_before_hours}（CSV・例 {@code "24,1"}）の各時間 h について
     * {@code remindAt = slotStartAt.minusHours(h)} を算出し、現在時刻（注入 Clock）より未来のものだけを
     * {@link ReservationReminderService#generateReminders(Long, List)} に渡して生成する
     * （直近確定で 24h 前が既に過ぎている等の過去時刻はスキップ）。上限件数は Service 側で担保する。</p>
     *
     * @param event 予約確定イベント
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onReservationConfirmed(ReservationConfirmedEvent event) {
        try {
            ReservationPolicyEntity policy = policyService.getOrDefault(event.getTeamId());
            List<Integer> hours = parseRemindBeforeHours(policy.getRemindBeforeHours());
            if (hours.isEmpty()) {
                return;
            }

            LocalDateTime now = LocalDateTime.now(clock);
            LocalDateTime slotStartAt = event.getSlotStartAt();
            List<LocalDateTime> remindAtList = new ArrayList<>();
            for (Integer h : hours) {
                LocalDateTime remindAt = slotStartAt.minusHours(h);
                if (remindAt.isAfter(now)) {
                    remindAtList.add(remindAt);
                }
            }

            if (remindAtList.isEmpty()) {
                log.info("予約確定リマインダー: 未来のリマインド時刻が無いため生成なし: reservationId={}, slotStartAt={}",
                        event.getReservationId(), slotStartAt);
                return;
            }

            reminderService.generateReminders(event.getReservationId(), remindAtList);
        } catch (Exception e) {
            // AFTER_COMMIT の副作用失敗。確定 TX は既にコミット済みのため波及させず、正直に記録する。
            log.error("予約確定リマインダーの自動生成に失敗しました: reservationId={}, teamId={}",
                    event.getReservationId(), event.getTeamId(), e);
        }
    }

    /**
     * {@code remind_before_hours} の CSV を正整数のリストにパースする。
     *
     * <p>不正なトークン（非数値・0 以下）は無視する。{@link ReservationPolicyService} / Entity に
     * 専用ヘルパーが存在しないため本リスナーで自前パースする。</p>
     *
     * @param csv リマインド CSV（例 {@code "24,1"}）。null / 空は空リスト
     * @return 正整数の時間リスト（不正トークンは除外）
     */
    private List<Integer> parseRemindBeforeHours(String csv) {
        List<Integer> hours = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return hours;
        }
        for (String token : csv.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                int h = Integer.parseInt(trimmed);
                if (h > 0) {
                    hours.add(h);
                }
            } catch (NumberFormatException e) {
                log.warn("remind_before_hours に不正なトークンが含まれます（無視）: token='{}', csv='{}'", trimmed, csv);
            }
        }
        return hours;
    }
}
