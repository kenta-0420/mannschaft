package com.mannschaft.app.reservation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 予約メニュー部分更新リクエスト DTO（F03.4.1 §4 PATCH）。
 *
 * <p><b>null/未指定 = 据え置き</b>（親の PATCH セマンティクス踏襲）。
 * {@code lineIds} は null = 据え置き / 空配列 {@code []} = 全ライン提供可へ戻す / 列挙 = 全置換。
 * {@code clearPrice=true} で {@code price} を null（料金非表示）へ戻す
 * （このとき {@code price} は無視。null 据え置きと null 設定を区別 — 親 {@code clearApprovalMode} と同形）。</p>
 */
@Getter
@RequiredArgsConstructor
@Schema(name = "UpdateReservationMenuRequest", description = "予約メニュー部分更新リクエスト")
public class UpdateReservationMenuRequest {

    /** メニュー名（1〜100文字）。null = 据え置き。 */
    @Size(min = 1, max = 100)
    private final String name;

    /** 所要時間（分）。30の倍数・30〜480。変更は新規予約から適用（遡及なし原則）。null = 据え置き。 */
    private final Integer durationMinutes;

    /** 表示用料金（0 以上）。null = 据え置き（null 設定は {@code clearPrice} を使う）。 */
    @DecimalMin("0")
    @Digits(integer = 8, fraction = 2)
    private final BigDecimal price;

    /** true で price を null（料金非表示）へ戻す。このとき {@code price} は無視。 */
    private final Boolean clearPrice;

    /** メニュー説明（最大500文字）。null = 据え置き。 */
    @Size(max = 500)
    private final String description;

    /** 表示順（1〜20）。null = 据え置き。 */
    @Min(1)
    @Max(20)
    private final Integer displayOrder;

    /** 有効/無効。null = 据え置き。 */
    private final Boolean isActive;

    /** 提供可能ライン ID。null = 据え置き / 空配列 = 全ライン提供可 / 列挙 = 全置換。 */
    private final List<Long> lineIds;
}
