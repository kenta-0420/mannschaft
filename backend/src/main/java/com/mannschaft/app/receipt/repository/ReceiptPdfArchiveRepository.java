package com.mannschaft.app.receipt.repository;

import com.mannschaft.app.receipt.ReceiptArchiveKind;
import com.mannschaft.app.receipt.entity.ReceiptPdfArchiveEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 領収書 PDF 原本アーカイブのリポジトリ（F08.12 §3.3 / §3.4.1）。
 *
 * <p>PDF 取得の<b>正はこの表</b>である。{@code receipts.pdf_storage_key} は
 * {@code ORIGINAL} のキーの冗長キャッシュに過ぎない。</p>
 */
public interface ReceiptPdfArchiveRepository extends JpaRepository<ReceiptPdfArchiveEntity, UUID> {

    /** 一意制約が {@code (receipt_id, archive_kind)} であるため必ず 0 件か 1 件に定まる。 */
    Optional<ReceiptPdfArchiveEntity> findByReceiptIdAndArchiveKind(
            Long receiptId, ReceiptArchiveKind archiveKind);

    List<ReceiptPdfArchiveEntity> findByReceiptId(Long receiptId);

    /**
     * 保存期限が到来した（今日以前の）アーカイブ行を一覧する（AC-77・§9.5）。
     *
     * <p>【御裁可済み】期限到来分を機械的に削除するバッチは作らない
     * （欠損金事業年度は保存期間が 10 年に延びる等の例外があるため。§9.5）。
     * 本メソッドは一覧のみに使う。</p>
     */
    List<ReceiptPdfArchiveEntity> findByRetentionUntilLessThanEqualOrderByRetentionUntilAsc(LocalDate today);
}
