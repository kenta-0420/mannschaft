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

    /**
     * CMP-028 Phase C: 大会一覧の SQL 述語版（{@code TournamentService#listTournaments} 用）。
     *
     * <p>設計書 {@code docs/features/F00_content_visibility_resolver.md} 「F00 一本化方針」の実体は
     * 「二つ目の判定器を作るな」であって「SQL に書くな」ではない（既存流儀:
     * {@code ActivityResultRepository#findVisibleByScopeTypeAndScopeId}）。本クエリは
     * {@code MembershipBatchQueryService#resolveVisibleLevels} が返したラダー集合を
     * {@link com.mannschaft.app.common.visibility.mapping.TournamentVisibilityMapper#toFunctional}
     * で機能 enum に逆写像した {@code visibilities} と、大会専用軸 {@code PARTICIPANTS_ONLY}
     * （{@code StandardVisibility.CUSTOM}）の個別述語を OR で組み合わせる。</p>
     *
     * <p><b>PARTICIPANTS_ONLY の SQL 述語</b>: {@code TournamentVisibilityResolver#evaluateCustom}
     * と同一の判定
     * （{@code TournamentParticipantRepository#countActiveMemberOfAnyParticipantTeam}）を
     * {@code EXISTS} サブクエリとして書き下す。{@code tournament_participants ×
     * tournament_divisions × memberships} への JOIN は当該 Repository が既に本番で行っている
     * 判断に倣うものであり、新たにドメイン境界を破るものではない（{@code memberships} への
     * ID 参照 JOIN のみで FK は張らない・原則1）。</p>
     *
     * <p><b>status 正規化（{@code TournamentStatusMapper} と同一の意味論を SQL で再現）</b>:</p>
     * <ul>
     *   <li>{@code DRAFT}: 作成者本人または SystemAdmin のみ可視</li>
     *   <li>{@code OPEN/IN_PROGRESS/COMPLETED}（PUBLISHED 区分）: visibility ラダー述語 OR
     *       PARTICIPANTS_ONLY EXISTS 述語 OR SystemAdmin</li>
     *   <li>{@code CANCELLED/ARCHIVED}（ARCHIVED 区分）: SystemAdmin のみ可視</li>
     * </ul>
     *
     * <p><b>{@code statusFilter} は任意</b>: {@code null} なら全ステータス対象（上記の状態別述語で
     * 絞られる）。非 {@code null} なら該当ステータスのみに絞ったうえで同じ状態別述語を適用する。</p>
     *
     * <p><b>匿名（{@code viewerUserId == null}）の扱い</b>: {@code created_by = NULL} /
     * {@code m.user_id = NULL} の比較は常に false になるため、DRAFT・PARTICIPANTS_ONLY は
     * 明示分岐なしで自然に fail-closed になる。visibility ラダーは
     * {@code resolveVisibleLevels} が匿名時に {@code PUBLIC} のみを返すため
     * {@code visibilities} 自体が {@code {PUBLIC}} に縮退する。</p>
     *
     * @param orgId               組織 ID
     * @param statusFilter        絞り込みステータス名（{@code null} で全ステータス対象）
     * @param visibilities        {@code TournamentVisibilityMapper#toFunctional} が返した可視ラダー
     *                            の enum 名集合（{@code IN ()} 回避のため呼び出し側で非空を保証する）
     * @param viewerUserId        閲覧者 user_id（{@code null} 可、未認証）
     * @param viewerIsSystemAdmin 閲覧者が SystemAdmin か
     * @param pageable            ページング指定
     * @return 閲覧者に可視な大会エンティティのページ
     */
    @Query(value = "SELECT t.* FROM tournaments t "
            + "WHERE t.organization_id = :orgId "
            + "  AND t.deleted_at IS NULL "
            + "  AND (:statusFilter IS NULL OR t.status = :statusFilter) "
            + "  AND ( "
            + "    (t.status = 'DRAFT' AND (t.created_by = :viewerUserId OR :viewerIsSystemAdmin = true)) "
            + "    OR (t.status IN ('OPEN', 'IN_PROGRESS', 'COMPLETED') AND ( "
            + "        t.visibility IN (:visibilities) "
            + "        OR :viewerIsSystemAdmin = true "
            + "        OR (t.visibility = 'PARTICIPANTS_ONLY' AND EXISTS ( "
            + "            SELECT 1 FROM tournament_participants p "
            + "            JOIN tournament_divisions d ON p.division_id = d.id "
            + "            JOIN memberships m ON m.scope_type = 'TEAM' AND m.scope_id = p.team_id "
            + "            WHERE d.tournament_id = t.id "
            + "              AND p.status IN ('REGISTERED', 'ACTIVE') "
            + "              AND m.user_id = :viewerUserId AND m.left_at IS NULL)) "
            + "    )) "
            + "    OR (t.status IN ('CANCELLED', 'ARCHIVED') AND :viewerIsSystemAdmin = true) "
            + "  ) "
            + "ORDER BY t.created_at DESC",
            countQuery = "SELECT COUNT(*) FROM tournaments t "
            + "WHERE t.organization_id = :orgId "
            + "  AND t.deleted_at IS NULL "
            + "  AND (:statusFilter IS NULL OR t.status = :statusFilter) "
            + "  AND ( "
            + "    (t.status = 'DRAFT' AND (t.created_by = :viewerUserId OR :viewerIsSystemAdmin = true)) "
            + "    OR (t.status IN ('OPEN', 'IN_PROGRESS', 'COMPLETED') AND ( "
            + "        t.visibility IN (:visibilities) "
            + "        OR :viewerIsSystemAdmin = true "
            + "        OR (t.visibility = 'PARTICIPANTS_ONLY' AND EXISTS ( "
            + "            SELECT 1 FROM tournament_participants p "
            + "            JOIN tournament_divisions d ON p.division_id = d.id "
            + "            JOIN memberships m ON m.scope_type = 'TEAM' AND m.scope_id = p.team_id "
            + "            WHERE d.tournament_id = t.id "
            + "              AND p.status IN ('REGISTERED', 'ACTIVE') "
            + "              AND m.user_id = :viewerUserId AND m.left_at IS NULL)) "
            + "    )) "
            + "    OR (t.status IN ('CANCELLED', 'ARCHIVED') AND :viewerIsSystemAdmin = true) "
            + "  )",
            nativeQuery = true)
    Page<TournamentEntity> findVisibleByOrganizationId(
            @Param("orgId") Long orgId,
            @Param("statusFilter") String statusFilter,
            @Param("visibilities") Collection<String> visibilities,
            @Param("viewerUserId") Long viewerUserId,
            @Param("viewerIsSystemAdmin") boolean viewerIsSystemAdmin,
            Pageable pageable);
}
