package com.mannschaft.app.disclosure.repository;

import com.mannschaft.app.disclosure.DraftStatus;
import com.mannschaft.app.disclosure.entity.DisclosureFormDraftEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 重要事項説明書 ドラフトリポジトリ。
 * F09.14 設計書 §4 ドラフト API のクエリパターンに対応。
 */
public interface DisclosureFormDraftRepository
        extends JpaRepository<DisclosureFormDraftEntity, Long> {

    /**
     * ID で未削除のドラフトを取得する。
     */
    Optional<DisclosureFormDraftEntity> findByIdAndDeletedAtIsNull(Long id);

    /**
     * スコープ別ドラフト一覧（ページング）。
     */
    Page<DisclosureFormDraftEntity> findByScopeTypeAndScopeIdAndDeletedAtIsNull(
            String scopeType, Long scopeId, Pageable pageable);

    /**
     * スコープ × ステータス絞り込み。
     */
    Page<DisclosureFormDraftEntity> findByScopeTypeAndScopeIdAndStatusAndDeletedAtIsNull(
            String scopeType, Long scopeId, DraftStatus status, Pageable pageable);

    /**
     * 居室別ドラフト履歴（重説書再発行ナビ用）。
     */
    List<DisclosureFormDraftEntity> findByTargetDwellingUnitIdAndDeletedAtIsNullOrderByUpdatedAtDesc(
            Long targetDwellingUnitId);

    /**
     * 様式テンプレートを参照しているドラフト件数（テンプレ削除前提検査用）。
     */
    long countByTemplateIdAndDeletedAtIsNull(Long templateId);

    /**
     * スコープ別ドラフト総件数（50件上限の自動論理削除用）。
     */
    long countByScopeTypeAndScopeIdAndDeletedAtIsNull(String scopeType, Long scopeId);

    /**
     * スコープ別最古順（古いドラフトの自動論理削除用）。
     */
    List<DisclosureFormDraftEntity>
            findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByUpdatedAtAsc(
                    String scopeType, Long scopeId, Pageable pageable);
}
