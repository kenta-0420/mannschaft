package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.enums.VillageReportStatus;
import com.mannschaft.app.village.service.VillageReportService.ReportActionTaken;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 村内通報の解決リクエスト（F17.1 Phase 1 B7 §4.11）。
 *
 * @param resolution   解決後の状態（RESOLVED / DISMISSED）
 * @param actionTaken  実施したアクション（NONE / WARNED / CONTENT_REMOVED / BANNED / VILLAGE_ARCHIVED）
 * @param note         処理メモ（任意・最大 1000 文字）
 */
public record ReportResolveRequest(
        @NotNull VillageReportStatus resolution,
        @NotNull ReportActionTaken actionTaken,
        @Size(max = 1000) String note
) {
}
