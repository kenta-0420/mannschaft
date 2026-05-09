package com.mannschaft.app.disclosure.dto;

import jakarta.validation.constraints.Size;

/**
 * 重要事項説明書 出力リクエスト DTO（F09.14 Phase 2-β-4）。
 *
 * <p>設計書 §4 POST /disclosure-drafts/{draftId}/export のリクエストボディ。
 * 出力形式（PDF/EXCEL/WORD）はクエリパラメータ {@code format=} で受け取るため、
 * 本 DTO には含めない。</p>
 *
 * @param recipientNote          提出先メモ（例: 「○○仲介株式会社 山田様」）。任意
 * @param allowPersonalInfo      個人情報自動引用を許可するか（設計書 §6.2）
 */
public record DisclosureExportRequest(
        @Size(max = 500, message = "recipientNote は500文字以下で指定してください")
        String recipientNote,
        Boolean allowPersonalInfo
) {

    /**
     * フィールド未指定（空ボディ）でも動作するよう、null セーフな getter を提供する。
     */
    public boolean allowPersonalInfoFlag() {
        return Boolean.TRUE.equals(allowPersonalInfo);
    }
}
