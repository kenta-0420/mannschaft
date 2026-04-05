package com.mannschaft.app.receipt.repository;

import com.mannschaft.app.receipt.ReceiptScopeType;
import com.mannschaft.app.receipt.entity.ReceiptPresetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 領収書発行プリセットリポジトリ。
 */
public interface ReceiptPresetRepository extends JpaRepository<ReceiptPresetEntity, Long> {

    /**
     * スコープ内のプリセット一覧を取得する。
     */
    List<ReceiptPresetEntity> findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
            ReceiptScopeType scopeType, Long scopeId);

    /**
     * スコープ内のプリセット件数を取得する。
     */
    long countByScopeTypeAndScopeId(ReceiptScopeType scopeType, Long scopeId);

    /**
     * ID とスコープで検索する。
     */
    Optional<ReceiptPresetEntity> findByIdAndScopeTypeAndScopeId(
            Long id, ReceiptScopeType scopeType, Long scopeId);
}
