package com.mannschaft.app.team.service;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.team.dto.TeamOrgAffiliationResponse;
import com.mannschaft.app.team.dto.TeamOrgAffiliationResponse.AffiliationGroupRef;
import com.mannschaft.app.team.dto.TeamOrgAffiliationResponse.AffiliationPartyRef;
import com.mannschaft.app.team.dto.TeamOrgAffiliationResponse.AffiliationRequesterRef;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOrgAffiliationDirection;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
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
        return assembleRowsForTeam(teamId, rows.stream().map(AffiliationRow::from).toList());
    }

    /**
     * 1つのチームの加盟の行（Entity を経ない確定値。書き込みの直後の応答に使う）を共通表現へ組み立てる。入力の順序を保つ。
     *
     * @param teamId 行が属するチーム（すべての行の {@code teamId} と一致していること）
     * @param rows   加盟の行
     */
    public List<TeamOrgAffiliationResponse> assembleRowsForTeam(Long teamId, List<AffiliationRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        TeamEntity team = teamRepository.findById(teamId)
                .orElseThrow(() -> new IllegalStateException("加盟の行が指すチームが無い: teamId=" + teamId));
        AffiliationPartyRef teamRef = new AffiliationPartyRef(team.getSlug(), team.getName(), team.getIconUrl());

        Set<Long> organizationIds = new HashSet<>();
        Set<UUID> groupIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        for (AffiliationRow row : rows) {
            organizationIds.add(row.organizationId());
            if (row.groupId() != null) {
                groupIds.add(row.groupId());
            }
            if (row.invitedBy() != null) {
                userIds.add(row.invitedBy());
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
     * 複数チームにまたがる加盟の行（組織側の一覧。2-B2）を共通表現へ組み立てる。入力の順序を保つ。
     *
     * <p>チームも ID 集合でまとめて引く（SQL は1本）。行が指すチームが論理削除済みの行は、片付け（§4.5）の前の
     * 短い間だけ残りうる。その行はチームの名前を出せないため識別子を持たない最小の表現にし、黙って落とさない。</p>
     *
     * @param rows 加盟の行
     */
    public List<TeamOrgAffiliationResponse> assembleForOrganization(List<TeamOrgMembershipEntity> rows) {
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

        AffiliationPartyRef missingTeam = new AffiliationPartyRef(null, null, null);
        return rows.stream()
                .map(row -> toResponse(AffiliationRow.from(row), teams.getOrDefault(row.getTeamId(), missingTeam),
                        organizations, groups, displayNames))
                .toList();
    }

    private TeamOrgAffiliationResponse toResponse(
            AffiliationRow row, AffiliationPartyRef teamRef,
            Map<Long, TeamAffiliationOrganizationPort.OrganizationRef> organizations,
            Map<UUID, TeamAffiliationOrganizationPort.GroupRef> groups,
            Map<Long, String> displayNames) {
        TeamAffiliationOrganizationPort.OrganizationRef org = organizations.get(row.organizationId());
        if (org == null) {
            // 組織が削除済みの行は、片付け（§4.5）の前の短い間だけ残りうる。名前を出せないため一覧に載せない扱いにするが、
            // ここでは行の取りこぼしを黙って隠さず、識別子だけを持つ最小の表現にする
            org = new TeamAffiliationOrganizationPort.OrganizationRef(
                    row.organizationId(), null, null, null, false);
        }

        // 削除済みグループ・他組織のグループ・グループ機能 off の組織は「未分類」として扱う（null）
        AffiliationGroupRef groupRef = null;
        if (row.groupId() != null && org.groupsEnabled()) {
            TeamAffiliationOrganizationPort.GroupRef group = groups.get(row.groupId());
            if (group != null && Objects.equals(group.organizationId(), row.organizationId())) {
                groupRef = new AffiliationGroupRef(group.id(), group.name());
            }
        }

        boolean pending = row.status() == TeamOrgMembershipEntity.Status.PENDING;
        OffsetDateTime requestedAt = toOffset(row.invitedAt());
        return new TeamOrgAffiliationResponse(
                row.id(),
                row.status().name(),
                row.direction().name(),
                teamRef,
                new AffiliationPartyRef(org.slug(), org.name(), org.iconUrl()),
                groupRef,
                pending ? row.message() : null,
                row.invitedBy() == null || !displayNames.containsKey(row.invitedBy())
                        ? null
                        : new AffiliationRequesterRef(row.invitedBy(), displayNames.get(row.invitedBy())),
                requestedAt,
                toOffset(row.respondedAt()),
                pending && requestedAt != null ? requestedAt.plusDays(pendingTtlDays) : null);
    }

    /** 起きた瞬間を、オフセット付きの日時へ変換する（API は ISO-8601 オフセット付き。§10）。 */
    private static OffsetDateTime toOffset(Instant instant) {
        return instant == null ? null : instant.atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toOffsetDateTime();
    }

    /**
     * 組み立ての入力となる加盟の行。時刻は起きた瞬間（{@link Instant}）で持つ。
     *
     * <p>{@link TeamOrgMembershipEntity} の日時列はアプリの壁時計（JST）の {@code LocalDateTime} として読み書きされる
     * 既存の列である（§4.6）。その解釈（サーバ既定ゾーンの壁時計）は {@link #from} の1か所に閉じ込め、
     * 新しいコードには {@code LocalDateTime} を持ち込まない。</p>
     */
    public record AffiliationRow(Long id, Long teamId, Long organizationId, TeamOrgMembershipEntity.Status status,
                                 TeamOrgAffiliationDirection direction, UUID groupId, String message,
                                 Long invitedBy, Instant invitedAt, Instant respondedAt) {

        /** Entity の行を組み立ての入力へ写す（既存列の壁時計をサーバ既定ゾーンで瞬間へ解釈する）。 */
        public static AffiliationRow from(TeamOrgMembershipEntity entity) {
            return new AffiliationRow(entity.getId(), entity.getTeamId(), entity.getOrganizationId(),
                    entity.getStatus(), entity.getDirection(), entity.getGroupId(), entity.getMessage(),
                    entity.getInvitedBy(), toInstant(entity.getInvitedAt()), toInstant(entity.getRespondedAt()));
        }

        private static Instant toInstant(LocalDateTime wallClock) {
            return wallClock == null ? null : wallClock.atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toInstant();
        }
    }
}
