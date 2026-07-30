package com.mannschaft.app.reservation.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 予約作成リクエストDTO。
 *
 * <p>全 final ＋ 単一コンストラクタ（{@code @RequiredArgsConstructor}）を維持すること。
 * コンストラクタが 2 本以上になると Jackson が creator を決められず POST が 500 になる
 * （{@code feedback_dto_all_final_multi_constructor_jackson_no_creators}）。</p>
 */
@Getter
@RequiredArgsConstructor
public class CreateReservationRequest {

    @NotNull
    private final Long reservationSlotId;

    @NotNull
    private final Long lineId;

    @Size(max = 500)
    private final String userNote;

    /**
     * 毎週繰り返す週数（F03.4.5 §6.2 W2-5・additive）。
     *
     * <p>省略（null）または 1 = <b>従来どおりの単発予約</b>（{@code recurring_series_id} は NULL・AC-5-2）。
     * 2〜12 を指定すると<b>起点週を含めて</b>その週数ぶん、同一ライン・同一曜日・同一時間帯の枠を
     * まとめて予約する。満席・枠なし・予約不可枠の週はスキップし、結果明細
     * （{@code ReservationResponse.recurring.skippedWeeks}）で返す。</p>
     *
     * <p><b>上限 12 の検証は Service 層</b>（{@code RESERVATION_054}）で行い、ここには
     * {@code @Max} を置かない。{@code @Max} だと Bean Validation の汎用 400 になり、
     * FE が「12 週までです」という専用文言を出せない（AC-5-3 はエラーコードを固定している）。
     * 0 以下は業務上意味を持たない入力不正なので {@code @Min(1)} で早期に弾く。</p>
     */
    @Min(1)
    private final Integer repeatWeeks;
}
