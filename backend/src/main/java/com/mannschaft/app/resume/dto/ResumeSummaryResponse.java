package com.mannschaft.app.resume.dto;

import lombok.Builder;

/**
 * 履歴書バージョン一覧用サマリーレスポンス（F01.10）。
 *
 * <p>設計書: {@code docs/features/F01.10_mypage_resume.md} §5.3
 *
 * <p>一覧取得 {@code GET /api/v1/resumes} で返す軽量な DTO。
 * 子要素（学歴・職歴等）は含まない。
 */
@Builder
public record ResumeSummaryResponse(
        /** 履歴書 ID（UUID 文字列）。 */
        String id,
        /** バージョン名。 */
        String title,
        /** 証明写真が設定されているか。 */
        boolean hasPhoto,
        /** 出力時の元号フォーマット（"WESTERN" or "JAPANESE"）。 */
        String eraFormat,
        /** 最終更新日時（ISO-8601 タイムゾーン付き）。 */
        String updatedAt
) {
}
