package com.mannschaft.app.forms.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * フォーム提出 PDF Pre-signed ダウンロード URL レスポンス DTO（F05.7 Phase 11 第四陣 4-B）。
 *
 * <p>{@code GET /api/v1/{scopeType}/{scopeId}/form-submissions/{id}/pdf/download-url} のレスポンス。</p>
 */
@Getter
@RequiredArgsConstructor
public class FormPdfDownloadUrlResponse {

    /** Pre-signed ダウンロード URL */
    private final String downloadUrl;

    /** 有効期限（秒） */
    private final long expiresIn;
}
