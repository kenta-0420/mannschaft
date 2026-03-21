package com.mannschaft.app.receipt.repository;

import com.mannschaft.app.receipt.entity.ReceiptLineItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 領収書明細行リポジトリ。
 */
public interface ReceiptLineItemRepository extends JpaRepository<ReceiptLineItemEntity, Long> {

    /**
     * 領収書 ID で明細行を表示順で取得する。
     */
    List<ReceiptLineItemEntity> findByReceiptIdOrderBySortOrderAsc(Long receiptId);

    /**
     * 領収書 ID で明細行を削除する。
     */
    void deleteByReceiptId(Long receiptId);
}
