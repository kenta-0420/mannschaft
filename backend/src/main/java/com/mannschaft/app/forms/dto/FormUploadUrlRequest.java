package com.mannschaft.app.forms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * フォーム添付 Pre-signed アップロード URL リクエスト DTO（F05.7 Phase 11 第四陣 4-B）。
 *
 * <p>{@code POST /api/v1/{scopeType}/{scopeId}/form-submissions/{id}/upload-url} のリクエスト。
 * 署名 PNG（最大 500KB）と一般ファイル（最大 10MB）に対応する。</p>
 */
@Data
public class FormUploadUrlRequest {

    /** フィールドキー（form_template_fields.field_key） */
    @NotBlank
    private String fieldKey;

    /** アップロードするファイル名 */
    @NotBlank
    private String fileName;

    /** Content-Type（MIME） */
    @NotBlank
    private String contentType;

    /** ファイルサイズ（バイト） */
    @Positive
    private long fileSize;
}
