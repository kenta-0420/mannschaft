package com.mannschaft.app.team.service;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.team.dto.TeamOrgAffiliationResponse;
import com.mannschaft.app.team.dto.TeamOrgAffiliationResponse.AffiliationGroupRef;
import com.mannschaft.app.team.dto.TeamOrgAffiliationResponse.AffiliationPartyRef;
import com.mannschaft.app.team.dto.TeamOrgAffiliationResponse.AffiliationRequesterRef;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 加盟の行を、申請・招待・加盟の共通表現 {@link TeamOrgAffiliationResponse} へ組み立てる（F01.2.1 §10.4）。
 *
 * <p>一覧は行数に比例して SQL が増えないよう、組織・グループ・ユーザーの名前を<b>まとめて引く</b>
 * （チーム1本・組織1本・グループ1本・ユーザー1本。AC-G129）。</p>
 *
 * <p>本クラスは<b>トランザクションの外</b>で呼ぶ。他ドメイン（組織・ユーザー）の名前解決は、書き込みの
 * トランザクションへ持ち込まない。</p>
 *
 * <p>チーム側の経路（2-B1）はチームが1つに決まっているため、チームは {@code teamId} から1回だけ引く。
 * 組織側の一覧（2-B2）・招待（2-C）は行ごとにチームが異なるため、後続の部隊が同じ組み立てを使えるよう
 * チームの引き方だけを差し替えられる形にしてある（{@link #assembleForTeam}）。</p>
 */
@Component
public class TeamOrgAffiliationAssembler {

    private final TeamRepository teamRepository;
    private final TeamAffiliationOrganizationPort organizationPort;
    private final NameResolverService nameResolverService;
    private final long pendingTtlDays;

    public TeamOrgAffiliationAssembler(
            TeamRepository teamRepository,
            TeamAffiliationOrganizationPort organizationPort,
            NameResolverService nameResolverService,
            @Value("${mannschaft.affiliation.pending-ttl-days:60}") long pendingTtlDays) {
        this.teamRepository = teamRepository;
        this.organizationPort = organizationPort;
        this.nameResolverService = nameResolverService;
        this.pendingTtlDays = pendingTtlDays;
    }

    /**
     * 1つのチームの加盟の行を共通表現へ組み立てる。入力の順序を保つ。
     *
     * @param teamId 行が属するチーム（すべての行の {@code team_id} と一致していること）
     * @param rows   加盟の行
     */
    public List<TeamOrgAffiliationResponse> assembleForTeam(Long teamId, List<TeamOrgMembershipEntity> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        TeamEntity team = teamRepository.findById(teamId)
                .orElseThrow(() -> new IllegalStateException("加盟の行が指すチームが無い: teamId=" + teamId));
        AffiliationPartyRef teamRef = new AffiliationPartyRef(team.getSlug(), team.getName(), team.getIconUrl());

        Set<Long> organizationIds = new HashSet<>();
        Set<UUID> groupIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        for (TeamOrgMembershipEntity row : rows) {
            organizationIds.add(row.getOrganizationId());
            if (row.getGroupId() != null) {
                groupIds.add(row.getGroupId());
            }
            if (row.getInvitedBy() != null) {
                userIds.add(row.getInvitedBy());
            }
        }
        Map<Long, TeamAffiliationOrganizationPort.OrganizationRef> organizations =
                organizationPort.findOrganizationRefs(organizationIds);
        Map<UUID, TeamAffiliationOrganizationPort.GroupRef> groups = organizationPort.findAliveGroupRefs(groupIds);
        Map<Long, String> displayNames = nameResolverService.resolveUserDisplayNames(userIds);

        return rows.stream()
                .map(row -> toResponse(row, teamRef, organizations, groups, displayNames))
                .toList();
    }

    /**
     * 行ごとにチームが異なる加盟の行を共通表現へ組み立てる（組織側の一覧。招待の送信済み一覧など）。入力の順序を保つ。
     *
     * <p>チームもまとめて引く（チーム1本・組織1本・グループ1本・ユーザー1本。行数に比例して SQL を増やさない）。</p>
     *
     * @param rows 加盟の行（チームは問わない）
     */
    public List<TeamOrgAffiliationResponse> assembleAcrossTeams(List<TeamOrgMembershipEntity> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> teamIds = new HashSet<>();
        Set<Long> organizationIds = new HashSet<>();
        Set<UUID> groupIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        for (TeamOrgMembershipEntity row : rows) {
            teamIds.add(row.getTeamId());
            organizationIds.add(row.getOrganizationId());
            if (row.getGroupId() != null) {
                groupIds.add(row.getGroupId());
            }
            if (row.getInvitedBy() != null) {
                userIds.add(row.getInvitedBy());
            }
        }
        Map<Long, AffiliationPartyRef> teams = new java.util.HashMap<>();
        for (TeamEntity team : teamRepository.findAllById(teamIds)) {
            teams.put(team.getId(), new AffiliationPartyRef(team.getSlug(), team.getName(), team.getIconUrl()));
        }
        Map<Long, TeamAffiliationOrganizationPort.OrganizationRef> organizations =
                organizationPort.findOrganizationRefs(organizationIds);
        Map<UUID, TeamAffiliationOrganizationPort.GroupRef> groups = organizationPort.findAliveGroupRefs(groupIds);
        Map<Long, String> displayNames = nameResolverService.resolveUserDisplayNames(userIds);

        return rows.stream()
                .map(row -> toResponse(row,
                        // 論理削除済みのチームを指す行は、片付け（§4.5）の前の短い間だけ残りうる。名前を出せないので識別子なしの表現にする
                        teams.getOrDefault(row.getTeamId(), new AffiliationPartyRef(null, null, null)),
                        organizations, groups, displayNames))
                .toList();
    }

    private TeamOrgAffiliationResponse toResponse(
            TeamOrgMembershipEntity row, AffiliationPartyRef teamRef,
            Map<Long, TeamAffiliationOrganizationPort.OrganizationRef> organizations,
            Map<UUID, TeamAffiliationOrganizationPort.GroupRef> groups,
            Map<Long, String> displayNames) {
        TeamAffiliationOrganizationPort.OrganizationRef org = organizations.get(row.getOrganizationId());
        if (org == null) {
            // 組織が削除済みの行は、片付け（§4.5）の前の短い間だけ残りうる。名前を出せないため一覧に載せない扱いにするが、
            // ここでは行の取りこぼしを黙って隠さず、識別子だけを持つ最小の表現にする
            org = new TeamAffiliationOrganizationPort.OrganizationRef(
                    row.getOrganizationId(), null, null, null, false);
        }

        // 削除済みグループ・他組織のグループ・グループ機能 off の組織は「未分類」として扱う（null）
        AffiliationGroupRef groupRef = null;
        if (row.getGroupId() != null && org.groupsEnabled()) {
            TeamAffiliationOrganizationPort.GroupRef group = groups.get(row.getGroupId());
            if (group != null && Objects.equals(group.organizationId(), row.getOrganizationId())) {
                groupRef = new AffiliationGroupRef(group.id(), group.name());
            }
        }

        boolean pending = row.getStatus() == TeamOrgMembershipEntity.Status.PENDING;
        OffsetDateTime requestedAt = toOffset(row.getInvitedAt());
        return new TeamOrgAffiliationResponse(
                row.getId(),
                row.getStatus().name(),
                row.getDirection().name(),
                teamRef,
                new AffiliationPartyRef(org.slug(), org.name(), org.iconUrl()),
                groupRef,
                pending ? row.getMessage() : null,
                row.getInvitedBy() == null || !displayNames.containsKey(row.getInvitedBy())
                        ? null
                        : new AffiliationRequesterRef(row.getInvitedBy(), displayNames.get(row.getInvitedBy())),
                requestedAt,
                toOffset(row.getRespondedAt()),
                pending && requestedAt != null ? requestedAt.plusDays(pendingTtlDays) : null);
    }

    /** アプリの壁時計（JST）の LocalDateTime を、オフセット付きの日時へ変換する（API は ISO-8601 オフセット付き。§10）。 */
    private static OffsetDateTime toOffset(LocalDateTime wallClock) {
        return wallClock == null
                ? null
                : wallClock.atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toOffsetDateTime();
    }
}
