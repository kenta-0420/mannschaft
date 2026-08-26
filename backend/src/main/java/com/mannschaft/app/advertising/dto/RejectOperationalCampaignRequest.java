package com.mannschaft.app.advertising.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 運用型キャンペーン審査差戻しリクエスト（F09.19 §6.5）。
 *
 * <p>理由は必須 1〜500 文字。{@code ad_campaigns.reject_reason} に永続化され、
 * 再 submit 時に NULL クリアされる。</p>
 */
public record RejectOperationalCampaignRequest(
        @NotBlank
        @Size(max = 500)
        String reason
) {
}
