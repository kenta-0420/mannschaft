package com.mannschaft.app.reservation.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定期予約不可枠の<b>強行登録</b>により既存予約が管理者キャンセルされたことを表すイベント
 * （F03.4.5 §6.2 W2-5・殿の裁定 2026-07-30。Issue #2543 で通知対象を user_id 単位に束ねるよう是正）。
 *
 * <p>管理者が {@code forceCancelConflicting=true} でルールを登録/更新すると、90 日 horizon 内で
 * overlap する active 予約が一括キャンセルされる。<b>予約を勝手に消して黙っているのは許されない</b>ため、
 * 各申込者へ必ず通知する。通知は AFTER_COMMIT のリスナー
 * （{@code ReservationForceCancelNotificationEventListener}）で行い、
 * 登録トランザクションが確定してから初めて送る（ロールバックされた「幻のキャンセル通知」を作らない）。</p>
 *
 * <p><b>Issue #2543: 通知は申込者（user_id）につき 1 通</b>。1 つのグループ予約が兄弟スロット 2 枠に
 * 跨り、ルールのブロック時間帯が両方を覆う場合でも、overlap 判定に直接ヒットした行が複数になるだけで
 * 申込者は 1 人である。通知対象を user_id 単位に束ね、{@link #getCancelledSlots()} に
 * <b>そのユーザーについて実際にキャンセルされた枠を全て</b>（overlap しなかった兄弟行も含む）列挙する。</p>
 *
 * @see com.mannschaft.app.reservation.service.ReservationRecurringBlockedTimeService
 */
@Getter
@RequiredArgsConstructor
public class ReservationForceCancelledByBlockEvent {

    /** チームID（通知の scopeId）。 */
    private final Long teamId;

    /**
     * 通知の sourceId に使う代表予約ID（当該ユーザーのキャンセルされた予約のうち先頭の 1 件）。
     * 複数枠キャンセルの本文は {@link #getCancelledSlots()} が持つため、
     * これは F00 可視性判定の観測点としてのみ使う。
     */
    private final Long reservationId;

    /** 申込者ユーザーID（通知の宛先）。 */
    private final Long userId;

    /**
     * このユーザーについて実際にキャンセルされた枠の一覧（1 件以上・overlap しなかった
     * グループ兄弟行を含む・枠の日時昇順）。本文にはこの全件を列挙する（検分 MUST③と同じ思想で
     * 「本文に出る枠数」と「実際に消えた枠数」を一致させる）。
     */
    private final List<CancelledSlot> cancelledSlots;

    /** 管理者が入力した事由ラベル（「研修」等・本文に載せる。null 可）。 */
    private final String blockReason;

    /**
     * キャンセルされた 1 枠分の情報（本文の列挙に使う）。
     *
     * @param slotStartAt 枠の開始日時（null 可。枠が解決できなかった場合）
     * @param slotTitle   枠タイトル（null 可）
     */
    public record CancelledSlot(LocalDateTime slotStartAt, String slotTitle) {
    }
}
