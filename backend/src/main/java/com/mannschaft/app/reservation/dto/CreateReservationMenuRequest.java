package com.mannschaft.app.reservation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 予約メニュー作成リクエスト DTO（F03.4.1 §4 POST）。
 *
 * <p>Jackson 地雷回避（§4 手本）: {@code @Getter @RequiredArgsConstructor} ＋ 全フィールド
 * {@code final} ＋ <b>単一コンストラクタ</b>。{@code lineIds} の「省略=全ライン」の既定は
 * final DTO では表現できないため Service 層で null→空リスト正規化する。</p>
 */
@Getter
@RequiredArgsConstructor
@Schema(name = "CreateReservationMenuRequest", description = "予約メニュー作成リクエスト")
public class CreateReservationMenuRequest {

    /** メニュー名（1〜100文字・必須）。 */
    @NotBlank
    @Size(max = 100)
    private final String name;

    /** 所要時間（分）。30の倍数・30〜480（範囲/倍数の検証は Service 層 = RESERVATION_034）。 */
    @NotNull
    private final Integer durationMinutes;

    /** 表示用料金（0 以上・小数2桁以内）。null = 料金非表示。 */
    @DecimalMin("0")
    @Digits(integer = 8, fraction = 2)
    private final BigDecimal price;

    /** メニュー説明（最大500文字）。 */
    @Size(max = 500)
    private final String description;

    /** 表示順（1〜20）。省略時は未削除行の MAX(display_order)+1（既存 0 件なら 1）。 */
    @Min(1)
    @Max(20)
    private final Integer displayOrder;

    /** 提供可能ライン ID。省略/null/空配列 = 全ライン提供可（不正 ID は 400 = RESERVATION_035）。 */
    private final List<Long> lineIds;
}
