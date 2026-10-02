package com.mannschaft.app.team.repository;

import com.mannschaft.app.team.entity.TeamOrgAffiliationDirection;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * F01.2.1 §10.2・§10.3: 申請受付の設定画面と申請フォームが使う、加盟・制限の読み取り専用クエリ。
 *
 * <p>書き込みは持たない。加盟の書き込み（申請・承認など）は {@link TeamOrgMembershipRepository} 側の責務であり、
 * 本リポジトリは画面表示のための集計・状態引きだけを担う（読み取りを書き込み系の Repository に混ぜず、
 * 並行して加盟の書き込みを実装する部隊と同じファイルを奪い合わないため）。</p>
 */
public interface TeamOrgAffiliationApplicantReadRepository extends Repository<TeamOrgMembershipEntity, Long> {

    /**
     * 組織宛ての加盟行のうち、指定した状態・起点の件数（設定画面の {@code pendingApplicationCount}）。
     */
    long countByOrganizationIdAndStatusAndDirection(
            Long organizationId, TeamOrgMembershipEntity.Status status, TeamOrgAffiliationDirection direction);

    /**
     * 指定チーム群と組織の間の加盟行（PENDING / ACTIVE。同じ組み合わせは一意制約で最大1件）。
     */
    List<TeamOrgMembershipEntity> findByOrganizationIdAndTeamIdIn(Long organizationId, Collection<Long> teamIds);

    /**
     * 指定チーム群のうち、この組織への申請（TEAM_APPLY 方向）が制限中のチーム ID（§5.4 の判定）。
     *
     * <p>{@code kind='BLOCK' OR restricted_until > :now}。期限切れの冷却は無視する。
     * 冷却かブロックかは返さない（申請者側に区別して見せないため）。</p>
     */
    @Query("SELECT DISTINCT r.teamId FROM TeamOrgAffiliationRestrictionEntity r "
            + "WHERE r.organizationId = :organizationId AND r.teamId IN :teamIds "
            + "AND r.direction = com.mannschaft.app.team.entity.TeamOrgAffiliationDirection.TEAM_APPLY "
            + "AND (r.kind = com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionKind.BLOCK "
            + "     OR r.restrictedUntil > :now)")
    List<Long> findRestrictedApplyTeamIds(
            @Param("organizationId") Long organizationId,
            @Param("teamIds") Collection<Long> teamIds,
            @Param("now") Instant now);
}
