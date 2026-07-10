package com.mannschaft.app.reservation.repository;

import com.mannschaft.app.reservation.ReservationStatus;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 定期予約不可枠の409ガード/impact（F03.4.5 §4.3・W2-2）が用いる、90日horizon内の active 予約 ×
 * 枠情報の projection。曜日一致・時間帯 overlap の判定は Service 層（{@code checker} と同一の
 * 半開区間・3文字曜日変換）で行うため、本クエリは team・日付レンジ・ライン軸のみで絞り込む
 * （JPQL constructor expression の戻り値型・§4.3）。
 *
 * @param reservationId 予約ID
 * @param userId        予約者 user_id
 * @param slotId        枠ID
 * @param slotDate      枠の日付
 * @param lineId        枠のライン軸（共通枠は null）
 * @param staffUserId   枠の担当スタッフ user_id（共通枠は null）
 * @param startTime     枠の開始時刻
 * @param endTime       枠の終了時刻
 * @param status        予約ステータス（PENDING / CONFIRMED）
 */
public record ReservationRecurringOverlapRow(
        Long reservationId,
        Long userId,
        Long slotId,
        LocalDate slotDate,
        Long lineId,
        Long staffUserId,
        LocalTime startTime,
        LocalTime endTime,
        ReservationStatus status) {
}
