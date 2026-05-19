package com.mannschaft.app.forms.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * フォーム提出 PDF 生成レスポンス DTO（F05.7 Phase 11 第四陣 4-B）。
 *
 * <p>{@code POST /api/v1/{scopeType}/{scopeId}/form-submissions/{id}/pdf} のレスポンス。
 * 同期生成・S3 にアップロード済みの状態で返す。</p>
 */
@Getter
@RequiredArgsConstructor
public class FormPdfGenerateResponse {

    /** 提出ID */
    private final Long submissionId;

    /** 生成済み PDF の S3 オブジェクトキー */
    private final String pdfFileKey;

    /** PDF 生成日時 */
    private final LocalDateTime pdfGeneratedAt;
}
