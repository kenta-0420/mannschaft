package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.enums.VillageNewsletterFrequency;
import jakarta.validation.constraints.NotNull;

/**
 * 村ニュースレター設定の更新リクエスト（F17.1 Phase 3-β-E）。
 *
 * <p>HEADMAN / ELDER のみ。指定頻度の設定を upsert する。</p>
 */
public record NewsletterSettingUpdateRequest(
        @NotNull VillageNewsletterFrequency frequency,
        @NotNull Boolean isEnabled
) {
}
