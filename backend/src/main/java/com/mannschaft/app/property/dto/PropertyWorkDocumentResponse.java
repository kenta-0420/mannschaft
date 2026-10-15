package com.mannschaft.app.property.dto;

import com.mannschaft.app.property.DocumentKind;
import com.mannschaft.app.property.entity.PropertyWorkDocumentEntity;

import java.time.LocalDateTime;

/**
 * 物件履歴文書レスポンス DTO（F09.13 Phase 1-δ）。
 *
 * <p>設計書 §4 レスポンス例で {@code documents[]} 内の各要素として返却される。
 * F05.5 SharedFile 本体（ファイル名・サイズ等）は本フェーズではフィールドを
 * {@code sharedFileId} のみとし、フロントが別途 SharedFile API で詳細取得する想定。
 * 後続フェーズで joinedSharedFile 情報を埋め込む拡張が予定されている。</p>
 */
public record PropertyWorkDocumentResponse(
        Long id,
        Long packageId,
        Long sharedFileId,
        DocumentKind documentKind,
        Integer displayOrder,
        String note,
        Long createdBy,
        LocalDateTime createdAt) {

    public static PropertyWorkDocumentResponse from(PropertyWorkDocumentEntity entity) {
        return new PropertyWorkDocumentResponse(
                entity.getId(),
                entity.getPackageId(),
                entity.getSharedFileId(),
                entity.getDocumentKind(),
                entity.getDisplayOrder(),
                entity.getNote(),
                entity.getCreatedBy(),
                entity.getCreatedAt());
    }
}
