package com.mannschaft.app.circulation.repository;

import com.mannschaft.app.circulation.entity.CirculationCommentEntity;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.visibility.CirculationCommentVisibilityProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 回覧コメントリポジトリ。
 */
public interface CirculationCommentRepository extends JpaRepository<CirculationCommentEntity, Long> {

    /**
     * 文書IDでコメントをページング取得する。
     */
    Page<CirculationCommentEntity> findByDocumentIdOrderByCreatedAtAsc(Long documentId, Pageable pageable);

    /**
     * IDと文書IDでコメントを取得する。
     */
    Optional<CirculationCommentEntity> findByIdAndDocumentId(Long id, Long documentId);

    /**
     * 文書IDでコメント数を取得する。
     */
    long countByDocumentId(Long documentId);

    /**
     * F00 共通可視性基盤 — {@link CirculationCommentVisibilityProjection} を 1 SQL でバルク取得する。
     *
     * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.6 / §6.3.2 工程 6。</p>
     *
     * <p>コメントは visibility を持たないため、scopeType / scopeId を親文書 ({@link CirculationDocumentEntity})
     * と JOIN して取得する。{@code @SQLRestriction("deleted_at IS NULL")} により論理削除済の行は除外される。</p>
     *
     * @param ids 取得対象 circulation_comments.id 集合（空の場合は空 List を返す）
     * @return 実存する回覧コメントの Projection リスト
     */
    @Query("""
            SELECT new com.mannschaft.app.circulation.visibility.CirculationCommentVisibilityProjection(
                cc.id,
                cd.scopeType,
                cd.scopeId,
                cc.userId,
                cc.documentId)
            FROM CirculationCommentEntity cc
            JOIN CirculationDocumentEntity cd ON cc.documentId = cd.id
            WHERE cc.id IN :ids
            """)
    List<CirculationCommentVisibilityProjection> findVisibilityProjectionsByIdIn(
            @Param("ids") Collection<Long> ids);
}
