package com.mannschaft.app.resume.dto;

import lombok.Builder;

/**
 * 正式出力（export）レスポンス DTO（F01.10）。
 *
 * <p>設計書: {@code docs/features/F01.10_mypage_resume.md} §5.12.2
 *
 * <p>PDF / Excel の出力生成物を R2 に保存し、presigned URL と有効期限を返す。
 */
@Builder
public record ResumeExportResponse(
        /** presigned URL（TTL 5 分）。 */
        String downloadUrl,
        /** 生成されたファイル名（例: "20260525_履歴書_山田太郎.pdf"）。 */
        String fileName,
        /** presigned URL の有効期限（ISO 8601）。 */
        String expiresAt
) {
}
