package com.mannschaft.app.reservation.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * 予約グループレスポンスDTO（F03.4.3 §4・グループ専用 API の応答）。
 *
 * <p><b>フラット構造</b>のグループ詳細全量。一覧系 {@code ReservationResponse} に付く要約サブ record
 * {@code GroupSummaryDto}（§5.6 #10）とは別物（詳細の全量 vs 一覧の要約）。</p>
 */
@Builder(toBuilder = true)
@Getter
public class ReservationGroupResponse {

    /** 予約グループID（UUIDv7・アプリ層採番）。 */
    UUID groupId;

    Long teamId;

    Long userId;

    /** グループの現在ステータス（全兄弟行で同値・PENDING/CONFIRMED/...）。 */
    String status;

    /** 選択メニューID（メニューなしグループは null）。 */
    UUID menuId;

    /** メニュー名（削除済みメニューも履歴解決・G-14。メニューなしは null）。 */
    String menuName;

    Long lineId;

    String lineName;

    /** 予約日（全枠同一日）。 */
    LocalDate slotDate;

    /** 先頭枠の開始時刻。 */
    LocalTime startTime;

    /** 末尾枠の終了時刻。 */
    LocalTime endTime;

    /** 確保した枠数。 */
    Integer slotCount;

    /** メニューの表示用料金（メニューなしグループは null — 枠単価の合算は表示しない・§4）。 */
    BigDecimal price;

    /** ユーザー備考（代表行に保存）。 */
    String userNote;

    LocalDateTime bookedAt;

    LocalDateTime confirmedAt;

    LocalDateTime cancelledAt;

    /** キャンセル実行者（USER / ADMIN。未キャンセルは null）。 */
    String cancelledBy;

    String cancelReason;

    /** グループを構成する予約行（枠の時系列昇順）。 */
    List<ReservationGroupItemDto> reservations;

    /**
     * グループ構成行（1 枠 = 1 予約行）。
     *
     * @param reservationId  予約ID
     * @param slotId         予約枠ID
     * @param startTime      枠の開始時刻
     * @param endTime        枠の終了時刻
     * @param isGroupPrimary 代表行フラグ（先頭枠のみ true）
     */
    public record ReservationGroupItemDto(
            Long reservationId, Long slotId, LocalTime startTime, LocalTime endTime, Boolean isGroupPrimary) {
    }
}
