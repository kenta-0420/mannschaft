package com.mannschaft.app.disclosure.dto;

import com.mannschaft.app.disclosure.DisclosureOutputFormat;
import com.mannschaft.app.disclosure.entity.DisclosureExportEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 重要事項説明書 出力履歴 レスポンス DTO（F09.14 Phase 2-β-4）。
 *
 * <p>設計書 §4 出力 API のレスポンス形状。
 * 出力直後は {@code downloadUrl} と {@code expiresAt} を含めて返却し、
 * クライアントは presigned URL に直接アクセスしてダウンロードする。
 * 履歴一覧では {@code downloadUrl}/{@code expiresAt} を null にしてもよい。</p>
 *
 * @param exportId                    出力履歴 ID（disclosure_exports.id）
 * @param scopeType                   スコープ種別
 * @param scopeId                     スコープ ID
 * @param draftId                     元ドラフト ID（削除済の場合 null）
 * @param templateId                  使用様式 ID
 * @param templateCodeSnapshot        様式コード（出力時点）
 * @param templateVersionSnapshot     様式バージョン（出力時点）
 * @param outputFormat                出力形式（PDF/EXCEL/WORD）
 * @param sharedFileId                F05.5 SharedFile.id
 * @param targetDwellingUnitId        対象居室 ID（任意）
 * @param requesterUserId             出力者ユーザー ID
 * @param recipientNote               提出先メモ
 * @param referencedPackageIds        引用済み履歴パッケージ ID 配列（除外も含めた事前検証結果）
 * @param sha256                      SHA-256 ダイジェスト（改ざん検出）
 * @param downloadUrl                 presigned ダウンロード URL（出力直後 / DL 要求時のみ）
 * @param downloadUrlExpiresAt        presigned URL の有効期限（出力直後 / DL 要求時のみ）
 * @param expiresAt                   出力履歴自体の自動削除予定日
 * @param createdAt                   出力日時
 * @param warnings                    出力時警告（例: 引用元が論理削除済で除外したパッケージのタイトル等）
 */
public record DisclosureExportResponse(
        Long exportId,
        String scopeType,
        Long scopeId,
        Long draftId,
        Long templateId,
        String templateCodeSnapshot,
        String templateVersionSnapshot,
        DisclosureOutputFormat outputFormat,
        Long sharedFileId,
        Long targetDwellingUnitId,
        Long requesterUserId,
        String recipientNote,
        List<Long> referencedPackageIds,
        String sha256,
        String downloadUrl,
        LocalDateTime downloadUrlExpiresAt,
        LocalDateTime expiresAt,
        LocalDateTime createdAt,
        List<String> warnings
) {

    /**
     * Entity から履歴用レスポンスへ変換する（downloadUrl 等は null）。
     */
    public static DisclosureExportResponse fromHistory(DisclosureExportEntity entity,
                                                       List<Long> referencedPackageIds) {
        return new DisclosureExportResponse(
                entity.getId(),
                entity.getScopeType(),
                entity.getScopeId(),
                entity.getDraftId(),
                entity.getTemplateId(),
                entity.getTemplateCodeSnapshot(),
                entity.getTemplateVersionSnapshot(),
                entity.getOutputFormat(),
                entity.getSharedFileId(),
                entity.getTargetDwellingUnitId(),
                entity.getRequesterUserId(),
                entity.getRecipientNote(),
                referencedPackageIds,
                entity.getOutputSha256(),
                null,
                null,
                entity.getExpiresAt(),
                entity.getCreatedAt(),
                List.of());
    }
}
