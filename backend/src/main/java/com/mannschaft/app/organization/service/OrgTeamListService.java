package com.mannschaft.app.organization.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.OrgErrorCode;
import com.mannschaft.app.organization.dto.OrgTeamSummaryResponse;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.organization.teamgroup.entity.OrgTeamGroupEntity;
import com.mannschaft.app.organization.teamgroup.repository.OrgTeamGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 組織の加盟チーム一覧（{@code GET /organizations/{slug}/teams}）の応答を組み立てる（F01.2.1 §10.1・4-B）。
 *
 * <p>本クラスは<b>トランザクションを持たない</b>。チーム・ロールの表を読む部分は
 * {@link OrganizationService#getTeams(Long)}（既存の入口）に任せ、組織のチームグループ（organization ドメインの表）を
 * 読んで、実効グループの判定・絞り込み・閲覧者ごとの出し分けをここで行う。1 つのトランザクションが複数ドメインの
 * Repository をまたがないための分離（CLAUDE.md 原則 5）。SQL の本数はチーム数・グループ数に比例しない（AC-G129）。</p>
 *
 * <p><b>実効グループ</b>: グループ機能が on で、{@code group_id} が<b>生存している</b>この組織のグループを指すときだけ
 * そのグループ。{@code group_id} が NULL・削除済みグループを指す（付け替えリスナーの処理前）・グループ機能 off は
 * 「未分類」として扱う（§4.4・§7.3）。絞り込みもこの実効グループで判定する。</p>
 */
@Service
@RequiredArgsConstructor
public class OrgTeamListService {

    private final OrganizationService organizationService;
    private final OrganizationRepository organizationRepository;
    private final OrgTeamGroupRepository orgTeamGroupRepository;

    /**
     * 加盟チーム一覧を返す。
     *
     * @param orgId            組織 ID
     * @param viewerSeesGroups 閲覧者が組織の MEMBER 以上（または SYSTEM_ADMIN）か。false のときは teamGroup を出さない
     * @param teamGroupId      絞り込むグループ（null なら絞らない。他組織・削除済み・不在のグループは空の結果になる）
     * @param unassigned       true なら未分類のチームだけに絞る（{@code teamGroupId} との併用は呼び出し側で拒否済み）
     * @throws BusinessException ORG_001（組織なし）
     */
    public List<OrgTeamSummaryResponse> list(Long orgId, boolean viewerSeesGroups, UUID teamGroupId,
                                             boolean unassigned) {
        List<OrgTeamMembershipView> teams = organizationService.getTeams(orgId);
        if (teams.isEmpty()) {
            return List.of();
        }
        OrganizationEntity org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new BusinessException(OrgErrorCode.ORG_001));
        Map<UUID, OrgTeamGroupEntity> aliveGroups = new HashMap<>();
        if (Boolean.TRUE.equals(org.getTeamGroupsEnabled())) {
            orgTeamGroupRepository.findByOrganizationIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(orgId)
                    .forEach(g -> aliveGroups.put(g.getId(), g));
        }

        List<OrgTeamSummaryResponse> result = new ArrayList<>(teams.size());
        for (OrgTeamMembershipView team : teams) {
            OrgTeamGroupEntity group = team.groupId() == null ? null : aliveGroups.get(team.groupId());
            UUID effectiveGroupId = group == null ? null : group.getId();
            if (teamGroupId != null && !teamGroupId.equals(effectiveGroupId)) {
                continue;
            }
            if (unassigned && effectiveGroupId != null) {
                continue;
            }
            result.add(new OrgTeamSummaryResponse(
                    team.slug(),
                    team.slug(),
                    team.name(),
                    null,
                    team.visibility(),
                    team.memberCount(),
                    viewerSeesGroups && group != null
                            ? new OrgTeamSummaryResponse.OrgTeamSummaryGroupRef(
                                    group.getId(), group.getName(), group.getSortOrder())
                            : null));
        }
        return result;
    }
}
