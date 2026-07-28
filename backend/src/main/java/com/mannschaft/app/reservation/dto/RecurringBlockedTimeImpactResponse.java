package com.mannschaft.app.reservation.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 定期予約不可枠 登録前の影響プレビュー（F03.4.5 §4.3）レスポンスDTO。
 *
 * <p>判定対象は「今日から90日先までの active 予約（PENDING/CONFIRMED）」（§4.3・409 ガードと同一horizon）。
 * 管理画面用途のため予約者氏名を含めてよい（機能B {@code BlockedTimeImpactResponse} と同一方針）。
 * 本 API は完全に副作用ゼロ。</p>
 */
@Builder(toBuilder = true)
@Getter
public class RecurringBlockedTimeImpactResponse {

    /** overlap する active 予約の件数。 */
    int affectedCount;

    /** overlap する active 予約の一覧（管理用・氏名込み・日付昇順）。 */
    List<ImpactedReservationDto> reservations;

    /**
     * 影響を受ける予約 1 件の管理用サマリ。
     *
     * @param reservationId 予約ID
     * @param userId        予約者 user_id
     * @param userName      予約者表示名（NameResolver で一括解決）
     * @param slotId        枠ID
     * @param slotDate      枠の日付（週次ルールは複数日に該当し得るため必須）
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
            LocalDate slotDate,
            String staffName,
            LocalTime startTime,
            LocalTime endTime,
            String status) {}
}
