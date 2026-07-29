package com.mannschaft.app.reservation.service;

import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * 仮押さえ(PENDING)自動失効の実処理サービス（F03.4.5 §6.3・W2-6）。
 *
 * <p>スケジュール宣言（{@code @BatchEndpoint} / {@code @Scheduled} / {@code @SchedulerLock}）は
 * {@link ReservationPendingExpireBatchService} が持ち、本クラスは「対象抽出」と「1 単位の失効」だけを担う
 * （{@link ReservationWaitlistCleanupBatchService} → {@link ReservationWaitlistService} と同じ役割分担。
 * 注入 {@code Clock} も実処理側である本クラスが保持する）。</p>
 *
 * <h2>なぜ 2 クラスに分けるのか（トランザクション境界の根治）</h2>
 * <p>バッチ全体を 1 つの {@code @Transactional} で囲むと、行単位 try/catch は<b>機能しない</b>。
 * 内側の {@code @Transactional} メソッド（{@code ReservationSlotService.decrementAndReopen} 等）から
 * 例外が抜けた時点で Spring は参加中トランザクションを rollback-only にマークするため、
 * 呼び出し元が例外を握っても最終コミットが {@code UnexpectedRollbackException} で失敗し、
 * 「1 件の失敗が全件を巻き込む」ことになる。
 * 失効 1 単位を {@link Propagation#REQUIRES_NEW} の独立トランザクションにすることで、
 * <b>単位内は原子的（部分失効なし）・単位間は独立（1 件の失敗が他を巻き込まない）</b>を両立させる
 * （AC-6-6 / AC-6-9）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationPendingExpireService {

    /** 申込者へ送る通知の種別（{@code NotificationType.RESERVATION_PENDING_EXPIRED}）。 */
    static final String NOTIFICATION_TYPE = "RESERVATION_PENDING_EXPIRED";

    /** 通知 sourceType（F00 visibility / 受信権の判定キー・予約ドメイン共通）。 */
    static final String SOURCE_TYPE = "RESERVATION";

    /** {@code cancel_reason} に入れる定型文（DB 保存用・FE には出ないため i18n 対象外）。 */
    static final String CANCEL_REASON = "承認期限切れのため自動キャンセルされました";

    private final ReservationRepository reservationRepository;
    private final ReservationSlotRepository slotRepository;
    private final ReservationSlotService slotService;
    private final NotificationHelper notificationHelper;
    private final Clock clock;

    /**
     * 失効対象を「失効単位」（単枠予約 = 1 行 / グループ = 兄弟行全部）のリストとして抽出する。
     *
     * <p>クエリは固定 3 本（候補の代表行・グループ兄弟行・枠）で、対象件数に比例したクエリを出さない
     * （AC-6-17）。</p>
     *
     * @return 失効単位のリスト（対象なしなら空）
     */
    @Transactional(readOnly = true)
    public List<PendingExpireUnit> findExpirableUnits() {
        // W2-6 骨格コミット: 実装は green 化コミットで入れる。
        return List.of();
    }

    /**
     * 失効単位 1 件を独立トランザクションで失効させる。
     *
     * @param unit 失効単位
     * @return 失効させた予約行数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int expireUnit(PendingExpireUnit unit) {
        // W2-6 骨格コミット: 実装は green 化コミットで入れる。
        return 0;
    }

    /**
     * 失効単位。単枠予約は {@code rows} が 1 行、グループ予約は兄弟行全部を含む。
     *
     * @param primary   代表行（{@code is_group_primary = TRUE}）。通知の宛先・本文解決に使う
     * @param rows      失効させる全行（グループは兄弟行全部＝部分失効を作らないための単位）
     * @param slotsById 各行の枠（枠復帰 {@code decrementAndReopen} に渡す）
     */
    public record PendingExpireUnit(
            ReservationEntity primary,
            List<ReservationEntity> rows,
            Map<Long, ReservationSlotEntity> slotsById) {
    }
}
