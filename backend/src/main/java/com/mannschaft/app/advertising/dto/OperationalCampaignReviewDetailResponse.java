package com.mannschaft.app.advertising.dto;

import com.mannschaft.app.advertising.PricingModel;
import com.mannschaft.app.advertising.entity.AdCampaignEntity.CampaignStatus;
import com.mannschaft.app.membership.domain.ScopeType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 運用型キャンペーン審査詳細レスポンス（SYSTEM_ADMIN 向け。F09.19 §6.1 / §6.5・§8.5 審査要件）。
 *
 * <p>F09.19.1b: 審査官が承認/却下を判断するには「誰の・どのスコープの・どのクリエイティブか」が必要だが、
 * 審査キュー一覧の {@link OperationalCampaignResponse} は広告主名・scope・クリエイティブを含まない。本 DTO は
 * キャンペーン本体（一覧と同一の全フィールド）に加えて広告主帰属（advertiserAccountId / advertiserName /
 * scopeType / scopeId）とクリエイティブ一覧（{@link AdCreativeResponse}）を返す。</p>
 *
 * <p>クラス名は既存 {@link OperationalCampaignResponse} および OpenAPI schema 名との衝突回避のため
 * {@code ReviewDetail} サフィックスを付す（feedback_openapi_nested_schema_name_collision）。</p>
 */
public record OperationalCampaignReviewDetailResponse(
        // ─── キャンペーン本体（OperationalCampaignResponse と同一） ───
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
        LocalDateTime updatedAt,
        // ─── 審査に必要な広告主帰属（F09.19.1b 追補） ───
        /** 広告主アカウント id（advertiser_accounts.id）。 */
        Long advertiserAccountId,
        /** 広告主表示名（advertiser_accounts.company_name）。 */
        String advertiserName,
        /** スコープ種別（ORGANIZATION / TEAM）。 */
        ScopeType scopeType,
        /** スコープ id（organization_id または team_id）。 */
        Long scopeId,
        /** 当該キャンペーンのクリエイティブ一覧（ads テーブル）。 */
        List<AdCreativeResponse> creatives
) {
}
