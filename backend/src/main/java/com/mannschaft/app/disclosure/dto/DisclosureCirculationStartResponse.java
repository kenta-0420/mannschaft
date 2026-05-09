package com.mannschaft.app.disclosure.dto;

import com.mannschaft.app.circulation.CirculationStatus;

/**
 * 重要事項説明書 出力履歴の電子印鑑承認回覧開始レスポンス DTO（F09.14 Phase 3-D）。
 *
 * <p>F05.2 {@code CirculationDocumentEntity} 連携結果を返す。{@code circulationDocumentId} は
 * {@code disclosure_exports.circulation_document_id} に保存される（クロスドメイン FK は無いため
 * 整合性はアプリ層で保証）。</p>
 *
 * @param exportId              出力履歴 ID
 * @param circulationDocumentId 紐付けた回覧文書 ID
 * @param circulationStatus     回覧文書の現在のステータス（通常 {@code ACTIVE}）
 */
public record DisclosureCirculationStartResponse(
        Long exportId,
        Long circulationDocumentId,
        CirculationStatus circulationStatus
) {
}
