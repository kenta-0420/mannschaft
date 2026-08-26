package com.mannschaft.app.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * slug 可用性チェックのレスポンス DTO（F01.2 §5.9 チーム・組織共通）。
 *
 * <p>チーム／組織の slug を作成・変更する前のリアルタイム検証に使用する。</p>
 *
 * @param available 利用可能なら true
 * @param reason    利用不可の理由コード（利用可能時は null）
 *                  SLUG_REQUIRED / SLUG_INVALID_FORMAT / SLUG_RESERVED /
 *                  SLUG_TAKEN / SLUG_RETIRED
 */
@Schema(description = "slug 可用性チェック結果")
public record SlugAvailabilityResponse(
        @Schema(description = "利用可能なら true") boolean available,
        @Schema(description = "利用不可の理由コード（利用可能時は null）", nullable = true) String reason
) {
}
