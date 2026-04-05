package com.mannschaft.app.receipt.repository;

import com.mannschaft.app.receipt.ReceiptQueueStatus;
import com.mannschaft.app.receipt.ReceiptScopeType;
import com.mannschaft.app.receipt.entity.ReceiptQueueEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 発行待ちキューリポジトリ。
 */
public interface ReceiptQueueRepository extends JpaRepository<ReceiptQueueEntity, Long> {

    /**
     * スコープ内のキューアイテム一覧をステータスと作成日降順で取得する。
     */
    Page<ReceiptQueueEntity> findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
            ReceiptScopeType scopeType, Long scopeId, Pageable pageable);

    /**
     * スコープ内の指定ステータスのキューアイテムを取得する。
     */
    Page<ReceiptQueueEntity> findByScopeTypeAndScopeIdAndStatusOrderByCreatedAtDesc(
            ReceiptScopeType scopeType, Long scopeId, ReceiptQueueStatus status, Pageable pageable);

    /**
     * ID とスコープで検索する。
     */
    Optional<ReceiptQueueEntity> findByIdAndScopeTypeAndScopeId(
            Long id, ReceiptScopeType scopeType, Long scopeId);

    /**
     * 支払い実績 ID で検索する（重複チェック用）。
     */
    boolean existsByMemberPaymentId(Long memberPaymentId);

    /**
     * 複数 ID でキューアイテムを取得する。
     */
    List<ReceiptQueueEntity> findByIdIn(List<Long> ids);

    /**
     * スコープ内の PENDING 件数を取得する。
     */
    long countByScopeTypeAndScopeIdAndStatus(
            ReceiptScopeType scopeType, Long scopeId, ReceiptQueueStatus status);
}
