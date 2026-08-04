package com.mannschaft.app.reservation.dto;

import com.mannschaft.app.reservation.ReservationDayOfWeek;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalTime;

/**
 * 定期予約不可枠 部分更新リクエストDTO（F03.4.5 §4.6・PATCH・null=据え置き）。
 *
 * <p>{@code clearLineId=true} で対象ラインをチーム全体（NULL）へ戻す
 * （週間テンプレート {@code UpdateSlotTemplateRequest} と同一作法）。
 * {@code startTime}/{@code endTime} は片方のみの更新も許容し、Service 層で現在値と合成してから
 * {@code SlotTimeValidator} で再検証する（全日型への変更は許可しない）。</p>
 */
@Getter
@RequiredArgsConstructor
public class UpdateRecurringBlockedTimeRequest {

    /** 対象ラインを変更する場合に指定（不正 ID は 400=LINE_NOT_FOUND）。 */
    private final Long lineId;

    /** TRUE のとき対象ラインをチーム全体（NULL）へ戻す（{@code lineId} と併用時は本フラグを優先）。 */
    private final Boolean clearLineId;

    /** 曜日を変更する場合に指定。 */
    private final ReservationDayOfWeek dayOfWeek;

    /** 開始時刻を変更する場合に指定。 */
    private final LocalTime startTime;

    /** 終了時刻を変更する場合に指定。 */
    private final LocalTime endTime;

    /** 事由ラベルを変更する場合に指定（100文字以内）。 */
    @Size(max = 100)
    private final String reason;

    /** 公開可否を変更する場合に指定。 */
    private final Boolean isPublic;

    /** 有効/無効を切替える場合に指定（一時停止）。 */
    private final Boolean isActive;

    /**
     * 衝突する既存予約を強行キャンセルして更新するか（F03.4.5 §6.2 W2-5・殿の裁定 2026-07-30・additive）。
     *
     * <p>未指定（null）/ FALSE = 従来どおり 409（{@code RESERVATION_027}）で拒否（挙動不変）。
     * TRUE = 更新後の最終形（曜日・時間帯・ライン）と overlap する active 予約を一括 CANCELLED にし、
     * 各申込者へ通知してから更新する。理由の詳細は
     * {@link CreateRecurringBlockedTimeRequest#getForceCancelConflicting()} を参照。</p>
     */
    private final Boolean forceCancelConflicting;
}
