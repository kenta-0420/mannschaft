package com.mannschaft.app.advertising.campaign.dto;

import com.mannschaft.app.advertising.campaign.enums.AdReportStatus;
import jakarta.validation.constraints.NotNull;

/**
 * F09.19.9 通報の対応状態更新リクエスト
 * （{@code PATCH /api/v1/system-admin/ad-user-reports/{id}/status}）。
 *
 * <p>許可遷移: NEW → REVIEWING → RESOLVED / DISMISSED（後退・飛び越しは AD_027 で拒否）。</p>
 *
 * @param status 遷移先状態
 */
public record UpdateAdReportStatusRequest(
        @NotNull(message = "遷移先状態は必須です")
        AdReportStatus status
) {
}
