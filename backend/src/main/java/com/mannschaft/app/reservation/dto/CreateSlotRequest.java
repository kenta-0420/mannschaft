package com.mannschaft.app.reservation.dto;

import com.mannschaft.app.reservation.ApprovalMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 予約スロット作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateSlotRequest {

    private final Long staffUserId;

    @Size(max = 200)
    private final String title;

    @NotNull
    private final LocalDate slotDate;

    @NotNull
    private final LocalTime startTime;

    @NotNull
    private final LocalTime endTime;

    /**
     * ライン軸（任意・F03.4.2 §3.1/§4）。
     *
     * <p>{@code null} / 未指定 = 共通枠（既存互換。予約時にユーザーがラインを選ぶ）。
     * 指定するとこの枠は「そのライン専用の枠」になる（予約時のライン選択は枠から自動決定）。
     * 当該チームの active ライン以外は 400（LINE_NOT_FOUND=001 再利用）。</p>
     *
     * <p>※ 旧 {@code recurrenceRule}（休眠足場）は F03.4.2 §3.3 で入力側を廃止した。
     * unknown property は Jackson 既定で無視されるため、送信しても壊れない（F-12）。</p>
     */
    private final Long lineId;

    private final BigDecimal price;

    @Size(max = 2000)
    private final String note;

    /**
     * 枠単位の承認モード上書き（任意）。
     *
     * <p>{@code null} / 未指定 = チーム既定（{@code reservation_policies}）に従う。
     * {@code AUTO} / {@code MANUAL} を指定するとこの枠だけチーム既定を上書きする。
     * 不正な enum 値は Jackson のデシリアライズ段階で 400 となる。</p>
     */
    private final ApprovalMode approvalMode;

    /**
     * 予約枠の定員（任意）。同時にこの枠を予約できる人数の上限。
     *
     * <p>{@code null} / 未指定 = 既定 1（＝美容院の 1:1 指名など、同一枠 1 名のみ）。
     * {@code booked_count} が {@code capacity} に達すると満席（受付終了）になる。1 以上を指定すること。</p>
     */
    @Min(1)
    private final Integer capacity;
}
