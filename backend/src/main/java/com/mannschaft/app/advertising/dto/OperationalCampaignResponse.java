package com.mannschaft.app.advertising.dto;

import com.mannschaft.app.advertising.PricingModel;
import com.mannschaft.app.advertising.entity.AdCampaignEntity.CampaignStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 運用型キャンペーンレスポンス（F09.19 §6.5 の CampaignResponse）。
 *
 * <p>クラス名は F09.17 メッセージ型の Campaign*Response 群および OpenAPI schema 名との衝突回避のため
 * Operational プレフィクスを付す（feedback_openapi_nested_schema_name_collision）。</p>
 */
public record OperationalCampaignResponse(
        Long id,
        String name,
        CampaignStatus status,
        PricingModel pricingModel,
        BigDecimal dailyBudget,
        LocalDate startDate,
        LocalDate endDate,
        Long rateCardId,
        /** 申込時単価スナップショット。作成時に rate_card.unit_price から確定・凍結。 */
        BigDecimal unitPriceSnapshot,
        /** 直近の審査差戻し理由。reject 時 SET・再 submit 時 null。 */
        String rejectReason,
        /** 通報自動停止時刻（F09.19.9 実装まで常に null）。 */
        LocalDateTime reportSuspendedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
