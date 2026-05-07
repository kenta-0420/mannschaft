package com.mannschaft.app.disclosure.repository;

import com.mannschaft.app.disclosure.entity.DisclosureExportEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 重要事項説明書 出力履歴リポジトリ。
 * F09.14 設計書 §4 出力履歴 API のクエリパターンに対応。
 */
public interface DisclosureExportRepository
        extends JpaRepository<DisclosureExportEntity, Long> {

    /**
     * ID で未削除の出力履歴を取得する。
     */
    Optional<DisclosureExportEntity> findByIdAndDeletedAtIsNull(Long id);

    /**
     * スコープ別出力履歴（ページング、新しい順）。
     */
    Page<DisclosureExportEntity> findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            String scopeType, Long scopeId, Pageable pageable);

    /**
     * 居室別出力履歴。
     */
    List<DisclosureExportEntity> findByTargetDwellingUnitIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            Long targetDwellingUnitId);

    /**
     * リクエスト者別出力履歴（レート制限・監査用）。
     */
    long countByRequesterUserIdAndCreatedAtAfter(Long requesterUserId, LocalDateTime since);

    /**
     * 様式テンプレートを参照している出力履歴件数（テンプレ削除前提検査用）。
     */
    long countByTemplateIdAndDeletedAtIsNull(Long templateId);

    /**
     * TTL 失効分の出力履歴を取得（自動削除バッチ用）。
     */
    @Query("""
            SELECT e FROM DisclosureExportEntity e
            WHERE e.deletedAt IS NULL
              AND e.expiresAt IS NOT NULL
              AND e.expiresAt <= :now
            ORDER BY e.expiresAt ASC
            """)
    List<DisclosureExportEntity> findExpired(@Param("now") LocalDateTime now, Pageable pageable);
}
