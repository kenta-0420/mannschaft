package com.mannschaft.app.reservation.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalTime;
import java.util.List;

/**
 * 予約不可枠 登録前の影響プレビュー（機能B・§4.B）レスポンスDTO。
 *
 * <p>overlap する既存 active 予約（{@code PENDING} / {@code CONFIRMED}）の件数＋一覧を返す。
 * <b>管理画面用途のため予約者氏名を含めてよい</b>（{@code getReservation} のような本人限定 PII とは別扱い）。
 * 本 API は<b>完全に副作用ゼロ</b>（{@code reservation_blocked_times} / {@code reservation_slots} を触らない）。</p>
 */
@Builder(toBuilder = true)
@Getter
public class BlockedTimeImpactResponse {

    /** overlap する active 予約の件数。 */
    int affectedCount;

    /** overlap する active 予約の一覧（管理用・氏名込み）。 */
    List<ImpactedReservationDto> reservations;

    /**
     * 影響を受ける予約 1 件の管理用サマリ。
     *
     * @param reservationId 予約ID
     * @param userId        予約者 user_id
     * @param userName      予約者表示名（NameResolver で一括解決）
     * @param slotId        枠ID
     * @param staffName     枠の担当スタッフ表示名（共通枠は null）
     * @param startTime     枠の開始時刻
     * @param endTime       枠の終了時刻
     * @param status        予約ステータス（PENDING / CONFIRMED）
     */
    public record ImpactedReservationDto(
            Long reservationId,
            Long userId,
            String userName,
            Long slotId,
            String staffName,
            LocalTime startTime,
            LocalTime endTime,
            String status) {}
}
