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

    /**
     * F08.7.1 主催大会サマリ: 組織の大会のうち、指定ステータスを除外して取得する。
     *
     * <p>設計書 02_dashboard_widgets.md §5.3 のセキュリティ要件に従い、未公開（DRAFT）の大会を
     * サマリ結果から除外する用途で使う（{@code excludeStatus = DRAFT} を渡す）。
     * 並び順は作成日降順（最新の大会を先頭に）。</p>
     *
     * @param organizationId 組織 ID
     * @param excludeStatus  除外するステータス（通常 {@link TournamentStatus#DRAFT}）
     * @return 大会一覧（DRAFT 除外・作成日降順）
     */
    List<TournamentEntity> findByOrganizationIdAndStatusNotOrderByCreatedAtDesc(
            Long organizationId, TournamentStatus excludeStatus);

    Page<TournamentEntity> findByOrganizationIdAndStatusOrderByCreatedAtDesc(
            Long organizationId, TournamentStatus status, Pageable pageable);

    Page<TournamentEntity> findByVisibilityAndStatusNotOrderByCreatedAtDesc(
            TournamentVisibility visibility, TournamentStatus excludeStatus, Pageable pageable);

    Page<TournamentEntity> findByOrganizationIdAndVisibilityAndStatusNotOrderByCreatedAtDesc(
            Long organizationId, TournamentVisibility visibility, TournamentStatus excludeStatus, Pageable pageable);

    /**
     * F00 Phase E-2: 公開大会一覧の Resolver 正規化クエリ。
     *
     * <p>{@link com.mannschaft.app.common.visibility.mapping.TournamentStatusMapper} の
     * PUBLISHED 区分（OPEN / IN_PROGRESS / COMPLETED）に限定することで、
     * 旧 {@code status != DRAFT} クエリが CANCELLED / ARCHIVED の PUBLIC も
     * 返してしまっていた既存バグを根治する。
     *
     * @param organizationId 組織 ID
     * @param visibility     公開設定（常に {@code TournamentVisibility.PUBLIC} を渡す）
     * @param statuses       許容ステータス集合（OPEN / IN_PROGRESS / COMPLETED）
     * @param pageable       ページネーション情報
     * @return ページネーション済み大会エンティティ
     */
    Page<TournamentEntity> findByOrganizationIdAndVisibilityAndStatusInOrderByCreatedAtDesc(
            Long organizationId, TournamentVisibility visibility,
            java.util.Collection<TournamentStatus> statuses, Pageable pageable);

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
