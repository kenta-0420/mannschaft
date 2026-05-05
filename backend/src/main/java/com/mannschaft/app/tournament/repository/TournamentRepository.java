package com.mannschaft.app.tournament.repository;

import com.mannschaft.app.tournament.TournamentStatus;
import com.mannschaft.app.tournament.TournamentVisibility;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.visibility.TournamentVisibilityProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * 大会リポジトリ。
 */
public interface TournamentRepository extends JpaRepository<TournamentEntity, Long> {

    Page<TournamentEntity> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId, Pageable pageable);

    Page<TournamentEntity> findByOrganizationIdAndStatusOrderByCreatedAtDesc(
            Long organizationId, TournamentStatus status, Pageable pageable);

    Page<TournamentEntity> findByVisibilityAndStatusNotOrderByCreatedAtDesc(
            TournamentVisibility visibility, TournamentStatus excludeStatus, Pageable pageable);

    Page<TournamentEntity> findByOrganizationIdAndVisibilityAndStatusNotOrderByCreatedAtDesc(
            Long organizationId, TournamentVisibility visibility, TournamentStatus excludeStatus, Pageable pageable);

    /**
     * F00 共通可視性基盤の射影取得。
     *
     * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.6 / §7.5。
     *
     * <p>{@link TournamentVisibilityProjection} に必要な
     * {@code id / scope_type='ORGANIZATION' / organization_id / created_by / status / visibility}
     * を JPQL のコンストラクタ式で 1 SQL に集約する。Tournament は組織配下固定のため
     * {@code scopeType} は常に文字列リテラル {@code 'ORGANIZATION'} を返す。
     *
     * <p>{@link TournamentEntity} には {@code @SQLRestriction("deleted_at IS NULL")} が
     * 付与されており、論理削除済の行は自動的に除外されるため、明示の WHERE 句は不要。
     * 本メソッドは Resolver の {@code AbstractContentVisibilityResolver#loadProjections} から
     * のみ呼ばれ、戻り値の順序は保証しない。
     *
     * @param ids 取得対象 tournament_id 集合（空の場合は空 List を返す）
     * @return 実存する tournaments の Projection リスト
     */
    @Query("""
            SELECT new com.mannschaft.app.tournament.visibility.TournamentVisibilityProjection(
                t.id,
                'ORGANIZATION',
                t.organizationId,
                t.createdBy,
                t.status,
                t.visibility)
            FROM TournamentEntity t
            WHERE t.id IN :ids AND t.deletedAt IS NULL
            """)
    List<TournamentVisibilityProjection> findVisibilityProjectionsByIdIn(
            @Param("ids") Collection<Long> ids);
}
