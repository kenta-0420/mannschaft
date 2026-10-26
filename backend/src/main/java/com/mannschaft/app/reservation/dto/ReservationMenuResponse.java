package com.mannschaft.app.reservation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 予約メニューレスポンス DTO（F03.4.1 §4）。
 *
 * <p>{@code requiredSlotCount} は BE 導出（{@code durationMinutes / 30}・FE で割り算を再実装させない）。
 * {@code lineIds} は空配列 = 全ライン提供可。会員/公開向けレスポンスでは削除済みラインの ID を
 * 露出させない（Service 層で内部フィルタ・§5）。</p>
 */
@Getter
@Builder
@Schema(name = "ReservationMenuResponse", description = "予約メニュー")
public class ReservationMenuResponse {

    /** メニューID（UUIDv7）。 */
    private final UUID id;

    /** メニュー名。 */
    private final String name;

    /** 所要時間（分・30の倍数）。 */
    private final Integer durationMinutes;

    /** 必要枠数 = durationMinutes / 30（BE 導出）。 */
    private final Integer requiredSlotCount;

    /** 表示用料金。null = 非表示。 */
    private final BigDecimal price;

    /** 説明。 */
    private final String description;

    /** 表示順。 */
    private final Integer displayOrder;

    /** 有効フラグ（会員向けレスポンスでは常に true の行のみ）。 */
    private final Boolean isActive;

    /** 提供可能ライン ID。空配列 = 全ライン提供可。 */
    private final List<Long> lineIds;

    /** 作成日時。 */
    private final LocalDateTime createdAt;
}
