package com.mannschaft.app.team.repository;

/**
 * {@link TeamOrgMembershipRepository#findOldestPendingOrgClosureGenerationByOrganization} の1行
 * （F01.2.1 2-D・片付けの回復バッチの候補選び）。JPQL の集計結果を受ける射影。
 */
public interface PendingClosureGenerationByOrganizationRow {

    Long getOrganizationId();

    /**
     * その組織の PENDING が作成時点に記録した組織の閉鎖の世代の最小値（{@code team_org_memberships.org_closure_generation}）。
     * 組織のいまの世代より小さければ、閉鎖より前に作られた PENDING が残っている（{@code TeamOrgClosureGeneration}）。
     */
    Long getOldestOrgClosureGeneration();
}
