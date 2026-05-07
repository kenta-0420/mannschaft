package com.mannschaft.app.property.dto;

import com.mannschaft.app.property.DocumentKind;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 物件履歴文書（F05.5 SharedFile 紐付け）作成リクエスト DTO（F09.13 Phase 1-δ）。
 *
 * <p>設計書 §3 property_work_documents に対応。実体の attach 処理は次フェーズの
 * {@code PropertyWorkDocumentService} で実装する想定で、本フェーズでは Controller
 * から service.attachDocument(packageId) を呼ぶカウンタ加算のみ実装する。</p>
 */
public record PropertyWorkDocumentRequest(
        @NotNull
        Long sharedFileId,

        @NotNull
        DocumentKind documentKind,

        Integer displayOrder,

        @Size(max = 500)
        String note) {
}
