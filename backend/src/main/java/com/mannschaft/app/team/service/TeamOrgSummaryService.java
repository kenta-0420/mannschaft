package com.mannschaft.app.team.service;

import com.mannschaft.app.team.dto.TeamOrgSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * チームの所属組織一覧（{@code GET /teams/{slug}/organizations}）の応答を組み立てる（F01.2.1 §10.1・4-B）。
 *
 * <p>本クラスは<b>トランザクションを持たない</b>。組織・ロールの表を読む部分は {@link TeamService#getOrganizations(Long)}
 * （既存の入口）に任せ、チームグループの名前は組織ドメインの窓口（{@link TeamAffiliationOrganizationPort}。SQL は 1 本）
 * から引く。1 つのトランザクションが複数ドメインの Repository をまたがないための分離（CLAUDE.md 原則 5）。
 * SQL の本数は加盟している組織の数に比例しない（AC-G129）。</p>
 *
 * <p>返すのは<b>自チームが所属するグループの id と名前だけ</b>（組織の他のグループは返さない。AC-F10）。
 * グループ機能が off の組織・未分類・削除済みグループを指す行・他組織のグループは null（未分類として扱う）。</p>
 */
@Service
@RequiredArgsConstructor
public class TeamOrgSummaryService {

    private final TeamService teamService;
    private final TeamAffiliationOrganizationPort organizationPort;

    /**
     * 所属組織一覧を返す。
     *
     * @param teamId          チーム ID
     * @param viewerSeesGroup 閲覧者がチームの MEMBER 以上（または SYSTEM_ADMIN）か。false のときは teamGroup を出さない
     */
    public List<TeamOrgSummaryResponse> list(Long teamId, boolean viewerSeesGroup) {
        List<TeamOrgMembershipSummary> memberships = teamService.getOrganizations(teamId);
        if (memberships.isEmpty()) {
            return List.of();
        }
        Map<UUID, TeamAffiliationOrganizationPort.GroupRef> groups = viewerSeesGroup
                ? organizationPort.findAliveGroupRefs(memberships.stream()
                        .map(TeamOrgMembershipSummary::groupId).filter(Objects::nonNull).toList())
                : Map.of();

        List<TeamOrgSummaryResponse> result = new ArrayList<>(memberships.size());
        for (TeamOrgMembershipSummary m : memberships) {
            TeamAffiliationOrganizationPort.GroupRef group = m.groupId() == null ? null : groups.get(m.groupId());
            boolean showGroup = group != null
                    && m.groupsEnabled()
                    && Objects.equals(group.organizationId(), m.organizationId());
            result.add(new TeamOrgSummaryResponse(
                    m.slug(),
                    m.slug(),
                    m.name(),
                    null,
                    m.visibility(),
                    m.memberCount(),
                    showGroup ? new TeamOrgSummaryResponse.TeamOrgSummaryGroupRef(group.id(), group.name()) : null));
        }
        return result;
    }
}
