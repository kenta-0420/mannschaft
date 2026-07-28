package com.mannschaft.app.reservation.dto;

import com.mannschaft.app.reservation.ReservationDayOfWeek;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalTime;

/**
 * 定期予約不可枠 作成リクエストDTO（F03.4.5 §4.6）。
 *
 * <p>親 §4.B の Jackson 手本（{@code @Getter @RequiredArgsConstructor}＋全 final＋単一コンストラクタ）に倣う
 * （{@code feedback_dto_all_final_multi_constructor_jackson_no_creators}）。</p>
 *
 * <p>全日型（start/end 欠落）は許可しない（{@code @NotNull} で 400・§4.3）。時刻の30分グリッド・
 * {@code start < end} 検証は既存 {@code SlotTimeValidator}（007/022 再利用）を Service 層で適用する。</p>
 */
@Getter
@RequiredArgsConstructor
public class CreateRecurringBlockedTimeRequest {

    /** 対象ライン。NULL = チーム全体。不正 ID は 400（LINE_NOT_FOUND=001 再利用）。 */
    private final Long lineId;

    /**
     * 曜日。<b>3文字大文字 {@code MON}..{@code SUN} のみ</b>。
     * 不正値（{@code MONDAY}/小文字/その他）は Jackson の enum デシリアライズ失敗で 400。
     */
    @NotNull
    private final ReservationDayOfWeek dayOfWeek;

    /** 開始（30分単位）。全日型は許可しないため必須（§4.3）。 */
    @NotNull
    private final LocalTime startTime;

    /** 終了（30分単位・start より後）。全日型は許可しないため必須（§4.3）。 */
    @NotNull
    private final LocalTime endTime;

    /** 事由ラベル（必須・100文字以内・§4.1）。 */
    @NotBlank
    @Size(max = 100)
    private final String reason;

    /** TRUE = 会員のマトリックス該当セルに reason を表示。未指定（null）は Service 層で FALSE に正規化する。 */
    private final Boolean isPublic;
}
