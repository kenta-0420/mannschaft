package com.mannschaft.app.common.architecture.fixtures;

import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.team.service.TeamOrgMembershipQueryService;

import java.util.List;

/**
 * fixture: 単一親前提の<b>縮約の検体</b>（実装を持つクラス）。
 *
 * <p>{@link com.mannschaft.app.common.architecture.TeamOrgSingleParentAssumptionGuardTest} が、
 * チーム→親組織の集合を取得した後に1件へ縮約する書き方を検出できること（偽陰性でないこと）と、
 * 集合のまま扱う書き方を検出しないこと（偽陽性でないこと）を確かめるために使う。
 * テスト側にあり本番のスキャン対象に入らない。わざと違反を書いてあるので、実コードの手本にしないこと。</p>
 */
public class SingleParentReductionFixture {

    private final TeamOrgMembershipRepository repository;
    private final TeamOrgMembershipQueryService queryService;

    public SingleParentReductionFixture(TeamOrgMembershipRepository repository,
                                        TeamOrgMembershipQueryService queryService) {
        this.repository = repository;
        this.queryService = queryService;
    }

    /** 違反: 加盟の集合を findFirst で任意の1件へ縮約する。 */
    public Long reduceByFindFirst(Long teamId) {
        return repository.findByTeamIdAndStatus(teamId, TeamOrgMembershipEntity.Status.ACTIVE)
                .stream()
                .findFirst()
                .map(TeamOrgMembershipEntity::getOrganizationId)
                .orElse(null);
    }

    /** 違反: 親組織 ID の一覧の先頭を List.get(0) で採る（並び順不定）。 */
    public Long reduceByListGet(Long teamId) {
        List<Long> parents = queryService.findActiveOrganizationIds(teamId);
        return parents.isEmpty() ? null : parents.get(0);
    }

    /** 本番では許可リストに明示される「代表親組織」の縮約の形（検体では許可がなければ検出される）。 */
    public Long representativeParent(Long teamId) {
        List<Long> parents = queryService.findActiveOrganizationIdsInPrimaryOrder(teamId);
        return parents.isEmpty() ? null : parents.get(0);
    }

    /** 違反: 許可リストに載せても、順序付き取得の「末尾」を採る（get(size - 1)）形は通らない。 */
    public Long representativeParentLast(Long teamId) {
        List<Long> parents = queryService.findActiveOrganizationIdsInPrimaryOrder(teamId);
        return parents.isEmpty() ? null : parents.get(parents.size() - 1);
    }

    /** 違反: 許可リストに載せても、順序付き取得を reduce で末尾へ縮約する形は通らない。 */
    public Long representativeParentByReduce(Long teamId) {
        return queryService.findActiveOrganizationIdsInPrimaryOrder(teamId)
                .stream()
                .reduce((first, second) -> second)
                .orElse(null);
    }

    /** 違反: 許可リストに載せても、順序付き取得を parallelStream().findAny() で任意の1件へ縮約する形は通らない。 */
    public Long representativeParentByFindAny(Long teamId) {
        return queryService.findActiveOrganizationIdsInPrimaryOrder(teamId)
                .parallelStream()
                .findAny()
                .orElse(null);
    }

    /** 違反: 許可リストに載せても、順序なしの取得（findByTeamIdAndStatus）へ差し替えた代表親取得は通らない。 */
    public Long representativeParentUnordered(Long teamId) {
        return repository.findByTeamIdAndStatus(teamId, TeamOrgMembershipEntity.Status.ACTIVE)
                .stream()
                .findFirst()
                .map(TeamOrgMembershipEntity::getOrganizationId)
                .orElse(null);
    }

    /** 正常: 親組織を取得して全件を走査する（拡張 for。縮約ではない）。 */
    public long scanAllParents(Long teamId) {
        long sum = 0;
        for (Long organizationId : queryService.findActiveOrganizationIds(teamId)) {
            sum += organizationId;
        }
        return sum;
    }

    /** 正常: 集合のまま包含判定するだけ。 */
    public boolean isParent(Long teamId, Long organizationId) {
        return queryService.findActiveOrganizationIds(teamId).contains(organizationId);
    }

    /** 正常: 集合に無関係の List.get は、親組織の取得が無ければ対象外。 */
    public Long unrelatedGet(List<Long> values) {
        return values.get(0);
    }
}
