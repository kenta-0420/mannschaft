package com.mannschaft.app.advertising.dto;

import com.mannschaft.app.advertising.PricingModel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 運用型キャンペーン作成/編集リクエスト（F09.19 §6.5）。
 *
 * <p>PUT（編集）も POST と同形の全フィールド送信（PATCH ではない）。
 * 業務バリデーション（AD_028 最低日予算 / AD_030 期間 / AD_031 料金カード）は Service で行う。</p>
 */
public record CreateOperationalCampaignRequest(

        /** キャンペーン名。必須。1〜200 文字。 */
        @NotBlank
        @Size(max = 200)
        String name,

        /** 課金モデル。必須。CPM | CPC。 */
        @NotNull
        PricingModel pricingModel,

        /** 日予算（円）。必須。選択 rate_card の min_daily_budget 以上（違反 AD_028）。 */
        @NotNull
        BigDecimal dailyBudget,

        /** 掲載開始日。必須。本日以降（違反 AD_030）。 */
        @NotNull
        LocalDate startDate,

        /** 掲載終了日。null 可（無期限）。非 null 時 startDate <= endDate（違反 AD_030）。 */
        LocalDate endDate,

        /** 料金カード ID。必須。pricingModel が一致し申込日が effective 期間内（違反 AD_031）。 */
        @NotNull
        Long rateCardId
) {
}
