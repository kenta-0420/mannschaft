package com.mannschaft.app.social.announcement.audience;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.organization.teamgroup.service.OrgTeamGroupService;
import com.mannschaft.app.social.announcement.AnnouncementFeedEntity;
import com.mannschaft.app.social.announcement.AnnouncementFeedGroupSnapshotEntity;
import com.mannschaft.app.social.announcement.AnnouncementFeedGroupSnapshotRepository;
import com.mannschaft.app.social.announcement.AnnouncementFeedQueryRepository;
import com.mannschaft.app.social.announcement.AnnouncementFeedQueryRepository.TeamDashboardFeedKey;
import com.mannschaft.app.team.service.TeamOrgMembershipQueryService;
import com.mannschaft.app.team.service.TeamOrgMembershipQueryService.TeamOrgGroupAssignment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
 * <p><b>判定の意味の正は本クラスの {@link #judge} だけ</b>。ダッシュボードの取得（{@link #findVisibleOrgFeeds}）は
 * DB 側で「judge が必ず落とす行」だけを前絞りし、残りを judge に通してから件数を数える。</p>
 *
 * <p>他ドメインへは Service 経由で ID・プリミティブだけを受け渡す（AC-G128）。トランザクションを持たない
 * （呼び出し元のダッシュボードも持たない）。各読み取りは呼び出し先の Service / Repository が自分の tx で行う
 * （tx の外で読むため、関連を持つ Entity の関連には触れない。スナップショットは feedId・groupId の列だけを読む）。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AnnouncementAudienceMatcher {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 組織告知を読み進める 1 ページの行数。 */
    static final int PAGE_SIZE = 50;

    /** 1 組織あたりに読み進める行数の上限（根拠は {@link #findVisibleOrgFeeds} の Javadoc）。 */
    static final int MAX_SCANNED_PER_ORG = 500;

    private final TeamOrgMembershipQueryService membershipQueryService;
    private final OrgTeamGroupService orgTeamGroupService;
    private final AnnouncementFeedGroupSnapshotRepository snapshotRepository;
    private final AnnouncementFeedQueryRepository feedQueryRepository;

    /**
     * チーム {@code teamId} のダッシュボードに出す組織告知を、宛先の判定を通ったものだけで、
     * 組織ごとにピン留め優先・新着順に最大 {@code limitPerOrg} 件ずつ返す（F01.2.1 §8.2・§15）。
     *
     * <p><b>件数の上限は判定の後に掛ける</b>。先に上位 N 件を取ってから判定すると、他グループ宛ての新しい
     * 告知が N 件以上あるとき、その後ろにある自チーム宛ての告知を取りこぼす。そこで組織ごとにキーセットで
     * {@value #PAGE_SIZE} 行ずつ読み進め、各ページを {@link #judge} に通し、{@code limitPerOrg} 件そろうか
     * 行が尽きるまで続ける。</p>
     *
     * <p>DB 側の前絞り（{@code AnnouncementFeedQueryRepository#findOrgScopePageForTeamDashboard}）は judge が
     * 必ず落とす行（どの表示条件にも当たり得ないグループ宛て）だけを除く。judge が表示する行は前絞りで決して
     * 除かれないため、結果は「全候補を judge に通して新しい順に limitPerOrg 件」と一致する
     * （{@code AnnouncementAudiencePagingConsistencyIT} が固定）。</p>
     *
     * <p><b>走査の上限</b>: 1 組織あたり {@value #MAX_SCANNED_PER_ORG} 行（{@value #PAGE_SIZE} 行 × 10 ページ）。
     * 1 ページは SQL 最大 2 本（フィード 1・スナップショット 0〜1）なので、1 組織あたり最悪 20 本で止まる
     * （ダッシュボード 1 回の応答時間を告知の履歴の長さに比例させないため）。前絞りの後に judge で落ちる行は
     * 「他チームを選んだ告知」「生存グループに所属するチームから見た、未分類を含む他グループ宛ての告知」
     * 「送信時の所属グループが今も生きていて、その後に別グループへ移ったチームのスナップショット」などに限られ、
     * 自チーム宛ての告知より新しいこれらが 500 行を超えて積み上がることは通常の運用では起きない。
     * 上限に達したときは取りこぼしを黙らせず WARN を残す。</p>
     *
     * <p>SQL 本数: チームの加盟 1、組織ごとにページ数 ×（フィード 1＋スナップショット 0〜1）、
     * 生存グループ 0〜1（グループ宛ての候補が現れたときに 1 回だけ）。1 ページに収まる限り告知の件数に比例しない。</p>
     *
     * @param teamId              閲覧中のチーム ID
     * @param allowedVisibilities 閲覧者が閲覧できる visibility 値の集合
     * @param limitPerOrg         1 組織あたりの最大件数（判定を通った後の件数）
     * @return 表示する組織告知（組織ごとにピン留め優先・新着順）
     */
    public List<AnnouncementFeedEntity> findVisibleOrgFeeds(Long teamId, Set<String> allowedVisibilities,
                                                            int limitPerOrg) {
        List<AnnouncementFeedEntity> visible = new ArrayList<>();
        if (teamId == null || allowedVisibilities == null || allowedVisibilities.isEmpty() || limitPerOrg <= 0) {
            return visible;
        }
        TeamContext ctx = loadContext(teamId);
        // チームが ACTIVE で加盟していない組織の告知は judge が必ず落とすので、読みに行かない（AC-E01・H25）
        for (Long orgId : ctx.activeOrgs()) {
            visible.addAll(findVisibleInOrg(ctx, orgId, allowedVisibilities, limitPerOrg));
        }
        return visible;
    }

    private List<AnnouncementFeedEntity> findVisibleInOrg(TeamContext ctx, Long orgId, Set<String> allowedVisibilities,
                                                          int limitPerOrg) {
        List<AnnouncementFeedEntity> picked = new ArrayList<>();
        TeamDashboardFeedKey after = null;
        int scanned = 0;
        while (picked.size() < limitPerOrg) {
            if (scanned >= MAX_SCANNED_PER_ORG) {
                log.warn("組織告知の走査が上限 {} 行に達したため打ち切ります（teamId={}, orgId={}, 表示 {} 件）。"
                                + "これより古い宛先一致の告知はダッシュボードに出ません",
                        MAX_SCANNED_PER_ORG, ctx.teamId(), orgId, picked.size());
                break;
            }
            List<AnnouncementFeedEntity> page = feedQueryRepository.findOrgScopePageForTeamDashboard(
                    orgId, allowedVisibilities, ctx.teamId(), ctx.groupByOrg().get(orgId), after, PAGE_SIZE);
            scanned += page.size();
            Set<Long> matched = judge(ctx, page);
            for (AnnouncementFeedEntity feed : page) {
                if (picked.size() >= limitPerOrg) {
                    break;
                }
                if (matched.contains(feed.getId())) {
                    picked.add(feed);
                }
            }
            if (page.size() < PAGE_SIZE) {
                break;
            }
            after = TeamDashboardFeedKey.of(page.get(page.size() - 1));
            if (after == null) {
                // created_at の無い行は位置にできない。読み進めず、黙らせずに痕跡を残す
                log.warn("created_at の無い組織告知があるため、それ以降を読み進めません（teamId={}, orgId={}）",
                        ctx.teamId(), orgId);
                break;
            }
        }
        return picked;
    }

    /** 候補フィードのうち、チーム {@code teamId} のダッシュボードに表示するものの ID を返す。 */
    public Set<Long> matchingFeedIds(Long teamId, Collection<AnnouncementFeedEntity> candidates) {
        if (teamId == null || candidates == null || candidates.isEmpty()) {
            return new LinkedHashSet<>();
        }
        return judge(loadContext(teamId), candidates);
    }

    /** チームの ACTIVE な加盟（組織と所属グループ）を 1 回の SQL で読み、判定の文脈を作る。 */
    private TeamContext loadContext(Long teamId) {
        Map<Long, UUID> groupByOrg = new HashMap<>();
        Set<Long> activeOrgs = new LinkedHashSet<>();
        for (TeamOrgGroupAssignment a : membershipQueryService.findActiveOrgGroupAssignments(teamId)) {
            activeOrgs.add(a.organizationId());
            groupByOrg.put(a.organizationId(), a.groupId());
        }
        return new TeamContext(teamId, activeOrgs, groupByOrg);
    }

    /**
     * 表示判定の本体（§8.2。判定の意味の唯一の正）。候補のうち表示するものの ID を返す。
     *
     * <p>生存グループは文脈ごとに 1 回だけ読む（グループ宛ての候補が現れたときだけ）。スナップショットは
     * 呼び出しごとに、その候補のうちグループ宛てのフィードの範囲だけを 1 回で読む。</p>
     */
    private Set<Long> judge(TeamContext ctx, Collection<AnnouncementFeedEntity> candidates) {
        Set<Long> matched = new LinkedHashSet<>();
        Set<Long> activeOrgs = ctx.activeOrgs();
        if (candidates == null || candidates.isEmpty() || activeOrgs.isEmpty()) {
            return matched;
        }

        List<AnnouncementFeedEntity> groupFeeds = candidates.stream()
                .filter(f -> activeOrgs.contains(f.getScopeId()) && !hasTeamSelection(f) && isGroupAudience(f))
                .toList();
        Set<UUID> liveGroups = Set.of();
        Map<Long, Set<String>> snapshotGroupsByFeed = Map.of();
        if (!groupFeeds.isEmpty()) {
            liveGroups = ctx.liveGroups(orgTeamGroupService);
            snapshotGroupsByFeed = new HashMap<>();
            for (AnnouncementFeedGroupSnapshotEntity s : snapshotRepository.findByTeamIdAndFeedIdIn(
                    ctx.teamId(), groupFeeds.stream().map(AnnouncementFeedEntity::getId).toList())) {
                snapshotGroupsByFeed.computeIfAbsent(s.getFeedId(), k -> new HashSet<>()).add(s.getGroupId());
            }
        }

        for (AnnouncementFeedEntity feed : candidates) {
            if (!activeOrgs.contains(feed.getScopeId())) {
                continue;
            }
            boolean show;
            if (hasTeamSelection(feed)) {
                show = teamSelected(feed.getTargetTeamIds(), ctx.teamId());
            } else if (isGroupAudience(feed)) {
                show = matchesGroups(feed, ctx.groupByOrg().get(feed.getScopeId()), liveGroups,
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

    /** 1 回の表示判定の文脈（チームの加盟・所属グループと、遅延して 1 回だけ読む生存グループ）。 */
    private static final class TeamContext {

        private final Long teamId;
        private final Set<Long> activeOrgs;
        private final Map<Long, UUID> groupByOrg;
        private Set<UUID> liveGroups;

        TeamContext(Long teamId, Set<Long> activeOrgs, Map<Long, UUID> groupByOrg) {
            this.teamId = teamId;
            this.activeOrgs = activeOrgs;
            this.groupByOrg = groupByOrg;
        }

        Long teamId() {
            return teamId;
        }

        Set<Long> activeOrgs() {
            return activeOrgs;
        }

        Map<Long, UUID> groupByOrg() {
            return groupByOrg;
        }

        Set<UUID> liveGroups(OrgTeamGroupService service) {
            if (liveGroups == null) {
                liveGroups = service.findLiveGroupIds(activeOrgs);
            }
            return liveGroups;
        }
    }
}
