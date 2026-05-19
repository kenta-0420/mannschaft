package com.mannschaft.app.advertising.campaign.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * F09.17 残課題 3 SYSTEM_ADMIN キャンペーン UNBLOCK リクエスト DTO。
 *
 * <p>誤 BLOCK の取消理由 {@code reason} を必須・最大 500 文字で受け付ける。
 * UNBLOCK 遷移先は {@code REVIEW}（再審査キューに戻す）。
 * APPROVED 直行は権限濫用リスクを避けるため認めない。</p>
 *
 * @param reason UNBLOCK 理由 (必須・最大 500 文字、監査ログ用)
 */
public record UnblockCampaignRequest(
        @NotBlank(message = "UNBLOCK 理由は必須です")
        @Size(max = 500, message = "UNBLOCK 理由は 500 文字以内で入力してください")
        String reason
) {
}
