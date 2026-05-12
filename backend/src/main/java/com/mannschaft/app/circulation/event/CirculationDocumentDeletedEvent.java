package com.mannschaft.app.circulation.event;

/**
 * 回覧文書削除時に F05.2 {@code CirculationService} が発行するドメインイベント。
 *
 * <p>F09.14 {@code DisclosureCirculationCleanupHandler} が購読し、
 * {@code disclosure_exports.circulation_document_id} を NULL 化する。
 * クロスドメイン FK を撤去した設計（CLAUDE.md ドメイン境界原則）の下で
 * アプリケーション層整合性を保証する役割を担う。</p>
 *
 * <p>将来 F05.5（ファイル共有）等の他ドメインが購読する余地あり。</p>
 *
 * @param documentId 削除された回覧文書 ID
 */
public record CirculationDocumentDeletedEvent(Long documentId) {
}
