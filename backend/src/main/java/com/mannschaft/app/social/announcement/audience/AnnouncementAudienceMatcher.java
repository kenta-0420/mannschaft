package com.mannschaft.app.social.announcement.audience;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.organization.teamgroup.service.OrgTeamGroupService;
import com.mannschaft.app.social.announcement.AnnouncementFeedEntity;
import com.mannschaft.app.social.announcement.AnnouncementFeedGroupSnapshotEntity;
import com.mannschaft.app.social.announcement.AnnouncementFeedGroupSnapshotRepository;
import com.mannschaft.app.team.service.TeamOrgMembershipQueryService;
import com.mannschaft.app.team.service.TeamOrgMembershipQueryService.TeamOrgGroupAssignment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 組織告知がチームのダッシュボードに表示されるかの判定（F01.2.1 §8.2）。
 *
 * <p>判定は次のとおり。いずれも「そのチームが告知の組織に今も ACTIVE で加盟している」ことが前提（離脱したら出ない。
 * 全チーム宛て・チームを選ぶも同じ。AC-E01・H25）。</p>
 * <ul>
 *   <li>全チーム宛て（宛先の記録なし）: 表示する</li>
 *   <li>チームを選ぶ（{@code target_team_ids}）: 選ばれていれば表示する（グループの移動・削除に影響されない。AC-H29）</li>
 *   <li>グループ宛て: 次のいずれかで表示する（OR）
 *     <ol>
 *       <li>動的: チームの現在の所属グループが生存していて宛先グループに含まれる（AC-H01・H02・H04）</li>
 *       <li>動的（未分類）: 現在の所属が NULL または削除済みグループで、告知が未分類を含む（AC-H03）</li>
 *       <li>スナップショット: 送信時にそのチームが対象だったグループが今は削除済み（AC-H20・H23・H24）</li>
 *     </ol></li>
 * </ul>
 *
 * <p>SQL は候補フィード数・グループ数に関係なく定数本（チームの加盟 1・生存グループ 1・スナップショット 1）。
 * 他ドメインへは Service 経由で ID・プリミティブだけを受け渡す（AC-G128）。トランザクションを持たない
 * （呼び出し元のダッシュボードも持たない）。各読み取りは呼び出し先の Service / Repository が自分の tx で行う
 * （tx の外で読むため、関連を持つ Entity の関連には触れない。スナップショットは feedId・groupId の列だけを読む）。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AnnouncementAudienceMatcher {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TeamOrgMembershipQueryService membershipQueryService;
    private final OrgTeamGroupService orgTeamGroupService;
    private final AnnouncementFeedGroupSnapshotRepository snapshotRepository;

    /** チーム {@code teamId} が今 ACTIVE で加盟している組織の ID（告知の候補を集める起点）。 */
    public Set<Long> activeOrganizationIds(Long teamId) {
        return new LinkedHashSet<>(membershipQueryService.findActiveOrganizationIds(teamId));
    }

    /** 候補フィードのうち、チーム {@code teamId} のダッシュボードに表示するものの ID を返す。 */
    public Set<Long> matchingFeedIds(Long teamId, Collection<AnnouncementFeedEntity> candidates) {
        Set<Long> matched = new LinkedHashSet<>();
        if (teamId == null || candidates == null || candidates.isEmpty()) {
            return matched;
        }
        Map<Long, UUID> groupByOrg = new HashMap<>();
        Set<Long> activeOrgs = new HashSet<>();
        for (TeamOrgGroupAssignment a : membershipQueryService.findActiveOrgGroupAssignments(teamId)) {
            activeOrgs.add(a.organizationId());
            groupByOrg.put(a.organizationId(), a.groupId());
        }
        if (activeOrgs.isEmpty()) {
            return matched;
        }

        List<AnnouncementFeedEntity> groupFeeds = candidates.stream()
                .filter(f -> activeOrgs.contains(f.getScopeId()) && !hasTeamSelection(f) && isGroupAudience(f))
                .toList();
        Set<UUID> liveGroups = Set.of();
        Map<Long, Set<String>> snapshotGroupsByFeed = Map.of();
        if (!groupFeeds.isEmpty()) {
            liveGroups = orgTeamGroupService.findLiveGroupIds(activeOrgs);
            snapshotGroupsByFeed = new HashMap<>();
            for (AnnouncementFeedGroupSnapshotEntity s : snapshotRepository.findByTeamIdAndFeedIdIn(
                    teamId, groupFeeds.stream().map(AnnouncementFeedEntity::getId).toList())) {
                snapshotGroupsByFeed.computeIfAbsent(s.getFeedId(), k -> new HashSet<>()).add(s.getGroupId());
            }
        }

        for (AnnouncementFeedEntity feed : candidates) {
            if (!activeOrgs.contains(feed.getScopeId())) {
                continue;
            }
            boolean show;
            if (hasTeamSelection(feed)) {
                show = teamSelected(feed.getTargetTeamIds(), teamId);
            } else if (isGroupAudience(feed)) {
                show = matchesGroups(feed, groupByOrg.get(feed.getScopeId()), liveGroups,
                        snapshotGroupsByFeed.getOrDefault(feed.getId(), Set.of()));
            } else {
                show = true;
            }
            if (show) {
                matched.add(feed.getId());
            }
        }
        return matched;
    }

    private static boolean matchesGroups(AnnouncementFeedEntity feed, UUID currentGroup, Set<UUID> liveGroups,
                                         Set<String> snapshotGroupIds) {
        boolean currentLive = currentGroup != null && liveGroups.contains(currentGroup);
        if (currentLive) {
            if (parseGroupIds(feed.getTargetGroupIds()).contains(currentGroup.toString())) {
                return true;
            }
        } else if (Boolean.TRUE.equals(feed.getIncludeUnassigned())) {
            return true;
        }
        // スナップショット判定: 送信時の対象グループが今は削除済みのものだけ（生存グループは上の動的判定が正）
        for (String groupId : snapshotGroupIds) {
            if (!isLive(groupId, liveGroups)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLive(String groupId, Set<UUID> liveGroups) {
        try {
            return liveGroups.contains(UUID.fromString(groupId));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean hasTeamSelection(AnnouncementFeedEntity feed) {
        return isPresentJson(feed.getTargetTeamIds());
    }

    /** グループ宛て（target_group_ids・include_unassigned・target_audience のいずれかの記録を持つ）か。 */
    private static boolean isGroupAudience(AnnouncementFeedEntity feed) {
        return isPresentJson(feed.getTargetGroupIds())
                || Boolean.TRUE.equals(feed.getIncludeUnassigned())
                || isPresentJson(feed.getTargetAudience());
    }

    private static boolean isPresentJson(String json) {
        return json != null && !json.isBlank() && !"null".equals(json);
    }

    private static boolean teamSelected(String targetTeamIds, Long teamId) {
        String needle = teamId.toString();
        return targetTeamIds.contains("\"" + needle + "\"")
                || targetTeamIds.matches(".*[\\[,]" + needle + "[,\\]].*");
    }

    private static Set<String> parseGroupIds(String json) {
        if (!isPresentJson(json)) {
            return Set.of();
        }
        try {
            return new HashSet<>(List.of(JSON.readValue(json, String[].class)));
        } catch (JsonProcessingException e) {
            // 壊れた記録は閉じる側（宛先なし）に倒す。握りつぶさず痕跡を残す
            log.warn("target_group_ids を読めないため宛先なしとして扱います: {}", json, e);
            return Set.of();
        }
    }
}
