package com.mannschaft.app.village.dto;

import lombok.Builder;

import java.util.List;
import java.util.UUID;

/**
 * 村のニュースレター設定一覧レスポンス（F17.1 Phase 3-β-E）。
 *
 * <p>WEEKLY / MONTHLY の 0〜2 件を一括返却する。設定が未作成の頻度は含まれない。</p>
 */
@Builder
public record NewsletterSettingsResponse(
        UUID villageId,
        List<NewsletterSettingResponse> settings,
        boolean optedOut
) {
}
