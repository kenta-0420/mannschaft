package com.mannschaft.app.circulation.repository;

import com.mannschaft.app.circulation.CirculationStatus;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.visibility.CirculationDocumentVisibilityProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 回覧文書リポジトリ。
 */
public interface CirculationDocumentRepository extends JpaRepository<CirculationDocumentEntity, Long> {

    /**
     * スコープ指定で文書をページング取得する。
     */
    Page<CirculationDocumentEntity> findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
            String scopeType, Long scopeId, Pageable pageable);

    /**
     * スコープとステータス指定で文書をページング取得する。
     */
    Page<CirculationDocumentEntity> findByScopeTypeAndScopeIdAndStatusOrderByCreatedAtDesc(
            String scopeType, Long scopeId, CirculationStatus status, Pageable pageable);

    /**
     * IDとスコープで文書を取得する。
     */
    Optional<CirculationDocumentEntity> findByIdAndScopeTypeAndScopeId(
            Long id, String scopeType, Long scopeId);

    /**
     * 作成者IDで文書をページング取得する。
     */
    Page<CirculationDocumentEntity> findByCreatedByOrderByCreatedAtDesc(Long createdBy, Pageable pageable);

    /**
     * スコープ指定でステータス別件数を取得する。
     */
    long countByScopeTypeAndScopeIdAndStatus(String scopeType, Long scopeId, CirculationStatus status);

    /**
     * F00 共通可視性基盤 — {@link CirculationDocumentVisibilityProjection} を 1 SQL でバルク取得する。
     *
     * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.6 / §6.3.2 工程 6。</p>
     *
     * <p>{@link CirculationDocumentEntity} の {@code @SQLRestriction("deleted_at IS NULL")} により論理削除済の
     * 行は自動的に除外されるため、明示の WHERE 句は不要。本メソッドは Resolver の
     * {@code AbstractContentVisibilityResolver#loadProjections} からのみ呼ばれ、戻り値の順序は保証しない。</p>
     *
     * @param ids 取得対象 circulation_documents.id 集合（空の場合は空 List を返す）
     * @return 実存する回覧文書の Projection リスト
     */
    @Query("""
            SELECT new com.mannschaft.app.circulation.visibility.CirculationDocumentVisibilityProjection(
                d.id,
                d.scopeType,
                d.scopeId,
                d.createdBy,
                d.status)
            FROM CirculationDocumentEntity d
            WHERE d.id IN :ids
            """)
    List<CirculationDocumentVisibilityProjection> findVisibilityProjectionsByIdIn(
            @Param("ids") Collection<Long> ids);
}
