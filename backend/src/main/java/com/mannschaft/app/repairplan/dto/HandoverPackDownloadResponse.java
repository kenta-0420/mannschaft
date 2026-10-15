package com.mannschaft.app.repairplan.dto;

import java.time.LocalDateTime;

/**
 * 申し送りパックダウンロード URL レスポンス（F08.8 Phase 5）。
 */
public record HandoverPackDownloadResponse(
        /** R2 署名付き URL（15分失効）。 */
        String downloadUrl,
        LocalDateTime expiresAt,
        /** ウォーターマーク情報（例: "田中理事 / 2026-05-14 10:32"）。 */
        String watermarkFor
) {
}
