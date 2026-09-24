package com.mannschaft.app.disclosure.service;

import com.mannschaft.app.circulation.event.CirculationDocumentDeletedEvent;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.disclosure.entity.DisclosureExportEntity;
import com.mannschaft.app.disclosure.repository.DisclosureExportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * 重要事項説明書 出力履歴と F05.2 回覧文書の参照整合性を保つクリーンアップハンドラ
 * （F09.14 Phase 3-D 配置 / Phase 4-C で有効化済み）。
 *
 * <p>背景: V61.017 で {@code disclosure_exports.circulation_document_id} のクロスドメイン FK
 * （fk_de_circulation）を撤去し index-only に置換した（CLAUDE.md ドメイン境界原則）。
 * これにより回覧文書（{@code circulation_documents}）が論理削除された場合に
 * DB 側で参照を NULL 化できないため、アプリケーション層で対処する。</p>
 *
 * <p><b>Phase 4-C で有効化済み</b>: F05.2 {@code CirculationService#deleteDocument} が
 * 論理削除直後に {@link CirculationDocumentDeletedEvent} を発行する。本ハンドラは
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} で購読し、削除コミット成功後に
 * 非同期で {@code disclosure_exports.circulation_document_id} を NULL 化する
 * （既存パターン {@code AuthAnonymizationEventListener} 踏襲）。</p>
 *
 * <p>ドメイン境界: F09.14 → 自ドメイン Repository のみ操作（F05.2 Entity への直接書き込みは禁止）。
 * F05.2 → イベント発行のみ（F09.14 への直接呼び出しは禁止）。疎結合維持。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DisclosureCirculationCleanupHandler {

    private final DisclosureExportRepository exportRepository;

    /**
     * 回覧文書削除イベントを受け取り、出力履歴側の参照を NULL 化する。
     *
     * <p>非同期＋独立トランザクションで実行する（既存パターン
     * {@code AuthAnonymizationEventListener} 踏襲）:
     * <ul>
     *   <li>{@code @Async("event-pool")}: イベント発行元の主要処理を阻害しない</li>
     *   <li>{@code REQUIRES_NEW}: クリーンアップ失敗が呼出側に伝播しない</li>
     *   <li>{@code AFTER_COMMIT}: 削除コミット成功後にのみ発火（ロールバック時は発火しない）</li>
     * </ul>
     * </p>
     *
     * @param event 回覧文書削除イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると削除済み回覧文書への参照が出力履歴に残る。クロスドメイン FK を撤去した代替であり、DB 側に整合を戻す手段が無い")
    @Async("event-pool")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCirculationDocumentDeleted(CirculationDocumentDeletedEvent event) {
        try {
            unlinkCirculationDocument(event.documentId());
        } catch (Exception e) {
            log.warn("DisclosureCirculationCleanupHandler 失敗: documentId={}", event.documentId(), e);
        }
    }

    /**
     * 指定の回覧文書 ID を参照する全 disclosure_exports について
     * {@code circulation_document_id} を NULL に戻す。
     *
     * <p>FK が無いため整合性アプリ層保証用のヘルパーとして提供する。</p>
     */
    void unlinkCirculationDocument(Long circulationDocumentId) {
        if (circulationDocumentId == null) {
            return;
        }
        List<DisclosureExportEntity> hits = exportRepository
                .findByCirculationDocumentIdAndDeletedAtIsNull(circulationDocumentId);
        if (hits.isEmpty()) {
            return;
        }
        for (DisclosureExportEntity entity : hits) {
            entity.linkCirculationDocument(null);
        }
        exportRepository.saveAll(hits);
        log.info("回覧文書削除に伴う出力履歴クリーンアップ: circulationDocumentId={}, 件数={}",
                circulationDocumentId, hits.size());
    }
}
