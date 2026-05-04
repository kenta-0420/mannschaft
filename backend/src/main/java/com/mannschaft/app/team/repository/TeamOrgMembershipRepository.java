package com.mannschaft.app.team.repository;

import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;


/**
 * チーム−組織所属リポジトリ。
 */
public interface TeamOrgMembershipRepository extends JpaRepository<TeamOrgMembershipEntity, Long> {

    Optional<TeamOrgMembershipEntity> findByTeamIdAndOrganizationId(Long teamId, Long organizationId);

    List<TeamOrgMembershipEntity> findByOrganizationIdAndStatus(Long organizationId, TeamOrgMembershipEntity.Status status);

    /**
     * チームが所属するACTIVE状態の組織を取得する（通常1件）。
     */
    Optional<TeamOrgMembershipEntity> findFirstByTeamIdAndStatus(Long teamId, TeamOrgMembershipEntity.Status status);

    /**
     * チームが所属する全組織を取得する。
     */
    List<TeamOrgMembershipEntity> findByTeamIdAndStatus(Long teamId, TeamOrgMembershipEntity.Status status);

    /**
     * 物理削除バッチ用: 指定ユーザーが招待者のレコードのinvitedByをNULL化する。
     */
    @Modifying
    @Query("UPDATE TeamOrgMembershipEntity m SET m.invitedBy = NULL WHERE m.invitedBy = :userId")
    int nullifyInvitedBy(@Param("userId") Long userId);

    /**
     * 物理削除バッチ用: 指定ユーザーが承認者のレコードのrespondedByをNULL化する。
     */
    @Modifying
    @Query("UPDATE TeamOrgMembershipEntity m SET m.respondedBy = NULL WHERE m.respondedBy = :userId")
    int nullifyRespondedBy(@Param("userId") Long userId);

    // ========================================================================
    // F00 ContentVisibilityResolver 基盤拡張 (Phase A-3b)
    //
    // ScopeAncestorResolver.resolveParentOrgIds() からバルク親 ORG 解決で利用される。
    // 設計書 docs/features/F00_content_visibility_resolver.md §5.1.1 / §10.2 参照。
    // ========================================================================

    /**
     * チーム ID 集合に対応する ACTIVE な親組織 ID をバルク取得する。
     *
     * <p>F00 基盤の {@code ScopeAncestorResolver} から呼ばれ、
     * {@code ORGANIZATION_WIDE} 公開判定および §11.6 親 ORG 連鎖チェックの土台となる。</p>
     *
     * <p>{@code teamIds} が空の場合は SQL を発行せず空 Map を即返却する。</p>
     *
     * @param teamIds 対象チーム ID 集合
     * @return チーム ID → 組織 ID のマップ。所属組織が見つからない team は entry に含めない
     */
    default Map<Long, Long> findOrganizationIdByTeamIdIn(Set<Long> teamIds) {
        if (teamIds == null || teamIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> result = new HashMap<>();
        for (TeamOrgIdProjection p : findTeamOrgIdProjectionsByTeamIdIn(teamIds)) {
            result.put(p.getTeamId(), p.getOrganizationId());
        }
        return result;
    }

    /**
     * {@link #findOrganizationIdByTeamIdIn(Set)} の内部 JPQL 実装。
     * 空集合チェックは default メソッド側で行うため、本メソッドは {@code teamIds}
     * 非空でのみ呼び出される。
     */
    @Query("SELECT m.teamId AS teamId, m.organizationId AS organizationId "
        + "FROM TeamOrgMembershipEntity m "
        + "WHERE m.teamId IN :teamIds "
        + "AND m.status = com.mannschaft.app.team.entity.TeamOrgMembershipEntity$Status.ACTIVE")
    List<TeamOrgIdProjection> findTeamOrgIdProjectionsByTeamIdIn(@Param("teamIds") Set<Long> teamIds);
}
