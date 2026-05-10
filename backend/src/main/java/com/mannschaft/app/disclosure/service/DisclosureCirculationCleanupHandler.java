package com.mannschaft.app.disclosure.service;

import com.mannschaft.app.disclosure.entity.DisclosureExportEntity;
import com.mannschaft.app.disclosure.repository.DisclosureExportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 重要事項説明書 出力履歴と F05.2 回覧文書の参照整合性を保つクリーンアップハンドラ
 * （F09.14 Phase 3-D）。
 *
 * <p>背景: V61.017 で {@code disclosure_exports.circulation_document_id} のクロスドメイン FK
 * （fk_de_circulation）を撤去し index-only に置換した。これにより回覧文書（{@code circulation_documents}）
 * が物理削除された場合に DB 側で参照を NULL 化できないため、アプリケーション層で対処する。</p>
 *
 * <p><b>本 PR の状態</b>: F05.2（circulation ドメイン）には現時点で
 * {@code CirculationDocumentDeletedEvent} 等のドメインイベント発行が無い。論理削除
 * （{@code deleted_at} セット）のみで物理削除はバッチでも行われていないため、参照は
 * 当面 NULL 化しなくても実害は小さい（@SQLRestriction により参照解決時には deleted_at
 * IS NULL のみ抽出される）。本ハンドラは将来 F05.2 がイベント発行を実装したタイミングで
 * 有効化する想定でスケルトンとして配置する。</p>
 *
 * <p><b>有効化手順（将来）</b>:
 * <ol>
 *   <li>F05.2 側で {@code CirculationDocumentDeletedEvent(documentId)} を
 *       {@code @Transactional} 内で {@code applicationEventPublisher.publishEvent} する</li>
 *   <li>本クラスの {@link #onCirculationDocumentDeleted(Long)} に
 *       {@code @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)} を付与し、
 *       {@code CirculationDocumentDeletedEvent} を購読する</li>
 *   <li>削除されたら {@link #unlinkCirculationDocument(Long)} で disclosure_exports 側の
 *       circulation_document_id を NULL 化する</li>
 * </ol>
 * </p>
 *
 * <p>TODO（Phase 4 以降）: F05.2 のイベント発行と本ハンドラの購読有効化。
 * F05.3 {@code seal_stamp_logs} の証跡参照を加えた改ざん検出強化（§6.3）も同時期に着手予定。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DisclosureCirculationCleanupHandler {

    private final DisclosureExportRepository exportRepository;

    /**
     * 回覧文書削除時に呼び出される処理。
     *
     * <p>現状は手動 / 他経路から呼び出すための public API として公開する。
     * 将来 F05.2 が {@code CirculationDocumentDeletedEvent} を発行するようになったら
     * 本メソッドに {@code @TransactionalEventListener} を付与する。</p>
     *
     * @param circulationDocumentId 削除された回覧文書 ID
     */
    @Transactional
    public void onCirculationDocumentDeleted(Long circulationDocumentId) {
        if (circulationDocumentId == null) {
            return;
        }
        unlinkCirculationDocument(circulationDocumentId);
    }

    /**
     * 指定の回覧文書 ID を参照する全 disclosure_exports について
     * {@code circulation_document_id} を NULL に戻す。
     *
     * <p>FK が無いため整合性アプリ層保証用のヘルパーとして提供する。
     * 通常の運用では {@link DisclosureCirculationService#startCirculation} 以外から
     * 参照書き換えが発生しないため、本処理が呼ばれるケースは稀であるべき。</p>
     */
    void unlinkCirculationDocument(Long circulationDocumentId) {
        // 既存リポジトリには derived query が無いため、ad-hoc に findAll 走査は重い。
        // 参照件数が多くないことを前提に、Phase 4 でクエリ最適化（@Query で UPDATE 直）を予定。
        // 本 PR では簡易実装として exportRepository から find する derived query を追加せず、
        // 呼び出し時に件数チェックを一度入れる構成にする。
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
