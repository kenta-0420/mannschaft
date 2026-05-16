package com.mannschaft.app.advertising.campaign.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * F09.17 Phase 11-a SYSTEM_ADMIN キャンペーンブロックリクエスト DTO。
 *
 * <p>ブロック理由 {@code reason} を必須・最大 500 文字で受け付ける。</p>
 *
 * @param reason ブロック理由 (必須・最大 500 文字)
 */
public record BlockCampaignRequest(
        @NotBlank(message = "ブロック理由は必須です")
        @Size(max = 500, message = "ブロック理由は 500 文字以内で入力してください")
        String reason
) {
}
