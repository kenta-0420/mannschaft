package com.mannschaft.app.social.announcement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.organization.teamgroup.service.OrgTeamGroupService;
import com.mannschaft.app.team.service.TeamOrgMembershipQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** preview 専用の最新配信対象判定。元記事の可視性とは独立して照合する。 */
@Service
@RequiredArgsConstructor
public class AnnouncementPreviewAudienceGuard {
    private final MembershipService membershipService;
    private final TeamOrgMembershipQueryService membershipQueryService;
    private final OrgTeamGroupService groupService;
    private final AnnouncementFeedGroupSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;

    public void assertIncluded(AnnouncementFeedEntity feed, Long viewerId, String role) {
        if (feed.getScopeType() != AnnouncementScopeType.ORGANIZATION || "SYSTEM_ADMIN".equals(role)) return;
        List<Long> teams = parse(feed.getTargetTeamIds(), new TypeReference<List<Long>>() {});
        List<String> groups = parse(feed.getTargetGroupIds(), new TypeReference<List<String>>() {});
        if (teams == null && groups == null && !Boolean.TRUE.equals(feed.getIncludeUnassigned())) return;
        if (membershipService.getActiveOrgIdsIncludingRoleAssignments(viewerId).contains(feed.getScopeId())) return;
        var memberships = membershipQueryService.findActiveMembershipsInOrganization(feed.getScopeId(),
                membershipService.getActiveTeamIdsIncludingRoleAssignments(viewerId));
        Set<UUID> groupIds = new HashSet<>();
        if (groups != null) groups.forEach(id -> groupIds.add(UUID.fromString(id)));
        memberships.stream().map(TeamOrgMembershipQueryService.ActiveGroupMembership::groupId)
                .filter(java.util.Objects::nonNull).forEach(groupIds::add);
        var deleted = groupService.findDeletedStates(feed.getScopeId(), groupIds);
        var snapshots = groups == null || groups.isEmpty() ? List.<AnnouncementFeedGroupSnapshotEntity>of()
                : snapshotRepository.findByFeedId(feed.getId());
        boolean included = memberships.stream().anyMatch(m -> {
            if (teams != null && teams.contains(m.teamId())) return true;
            if (groups != null && m.groupId() != null && groups.contains(m.groupId().toString())
                    && Boolean.FALSE.equals(deleted.get(m.groupId()))) return true;
            if (groups != null && snapshots.stream().anyMatch(s -> s.getTeamId().equals(m.teamId())
                    && groups.contains(s.getGroupId()) && Boolean.TRUE.equals(deleted.get(UUID.fromString(s.getGroupId()))))) return true;
            return Boolean.TRUE.equals(feed.getIncludeUnassigned())
                    && (m.groupId() == null || Boolean.TRUE.equals(deleted.get(m.groupId())));
        });
        if (!included) throw new BusinessException(AnnouncementErrorCode.ANNOUNCE_001);
    }

    private <T> List<T> parse(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank() || "null".equals(json.trim())) return null;
        try {
            List<T> values = objectMapper.readValue(json, type);
            if (values.stream().anyMatch(java.util.Objects::isNull)) throw new IllegalStateException("配信対象に null が含まれます");
            return values;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("保存された配信対象を解釈できません", e);
        }
    }
}
