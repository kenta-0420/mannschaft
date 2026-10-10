package com.mannschaft.app.social.announcement.audience;

import com.mannschaft.app.social.announcement.AnnouncementFeedEntity;
import com.mannschaft.app.social.announcement.AnnouncementFeedGroupSnapshotEntity;
import com.mannschaft.app.social.announcement.AnnouncementFeedGroupSnapshotRepository;
import com.mannschaft.app.social.announcement.AnnouncementFeedQueryRepository;
import com.mannschaft.app.social.announcement.AnnouncementFeedQueryRepository.TeamDashboardFeedKey;
import com.mannschaft.app.social.announcement.AnnouncementFeedRepository;
import com.mannschaft.app.social.announcement.AnnouncementScopeType;
import com.mannschaft.app.social.announcement.AnnouncementSourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F01.2.1 部隊 6-C — 組織告知の取得（DB の前絞り＋キーセット＋判定後の件数上限）が、
 * 判定の正である {@link AnnouncementAudienceMatcher#matchingFeedIds} と食い違わないことを固定する。
 *
 * <p>判定の意味の正は Java 側（Matcher）。DB 側の前絞り（{@code findOrgScopePageForTeamDashboard}）は
 * 「Matcher が必ず落とす行」だけを除く上位集合であるはずなので、次が成り立つ。</p>
 * <pre>
 *   findVisibleOrgFeeds(T, 20)
 *     ＝ 組織の全候補を Matcher に通し、ピン留め優先・新着順（同時刻は id 降順）に並べた先頭 20 件
 * </pre>
 * <p>候補には §8.2 の全分岐と前絞りの境界を置く: 全チーム宛て、チームを選ぶ、グループ宛て（動的）、
 * 未分類を含む、送信後に削除したグループ（スナップショット）、スナップショットはあるがグループが生存、
 * 所属が削除済みグループを指すチーム、{@code target_group_ids} が JSON の null、宛先の記録
 * （{@code target_audience}）だけを持つ行、ピン留め、同時刻。さらに 1 ページ（{@value AnnouncementAudienceMatcher#PAGE_SIZE} 行）を
 * 超える件数と、判定後に 20 件を超える件数を置き、キーセットで読み進める経路と判定後の打ち切りを通す。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 6-C 組織告知の取得は Matcher の判定と食い違わない（前絞り・キーセット・判定後の上限）")
class AnnouncementAudiencePagingConsistencyIT extends AbstractAudienceDisplayIT {

    private static final Set<String> ALL_VISIBILITIES = Set.of("PUBLIC", "SUPPORTERS_AND_ABOVE", "MEMBERS_AND_ABOVE");
    private static final int LIMIT = 20;
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 9, 1, 9, 0);

    @Autowired
    private AnnouncementAudienceMatcher matcher;

    @Autowired
    private AnnouncementFeedQueryRepository feedQueryRepository;

    @Autowired
    private AnnouncementFeedRepository feedRepository;

    @Autowired
    private AnnouncementFeedGroupSnapshotRepository snapshotRepository;

    private int seq;

    @BeforeEach
    void setUp() {
        setUpOrgX();
        seq = 0;

        // 全チーム宛て・チームを選ぶ
        insertFeed(null, null, false, null, false);
        insertFeed("[" + t1.getId() + "]", null, false, null, false);
        insertFeed("[" + t2.getId() + "]", null, false, null, true);
        insertFeed("[" + t1.getId() + "," + t3.getId() + "]", null, false, null, false);
        // グループ宛て（動的）・未分類を含む
        insertFeed(null, List.of(g1.getId()), false, null, false);
        insertFeed(null, List.of(g1.getId(), g2.getId()), false, null, true);
        insertFeed(null, List.of(g3.getId()), true, null, false);
        insertFeed(null, List.of(), true, null, false);
        // 宛先の記録だけを持つ（グループ宛て扱いで、どの表示条件にも当たらない）
        insertFeed(null, null, false, "{\"groupNames\":[\"G1\"]}", false);
        // スナップショット: G2 宛てを T2 が受けた後に G2 を削除する（T2 には出続ける）
        long g2Snap = insertFeed(null, List.of(g2.getId()), false, null, false);
        snapshot(g2Snap, g2.getId(), t2.getId());
        // スナップショットはあるがグループは生存: G3 宛てを T3 が受けた後に T3 を G1 へ移す（T3 には出ない）
        long g3Snap = insertFeed(null, List.of(g3.getId()), false, null, false);
        snapshot(g3Snap, g3.getId(), t3.getId());
        // target_group_ids が JSON の null（Matcher はグループ宛てと見なさない＝全チーム宛て）
        long jsonNull = insertFeed(null, null, false, null, false);
        em.createNativeQuery("UPDATE announcement_feeds SET target_group_ids = CAST('null' AS JSON) WHERE id = :id")
                .setParameter("id", jsonNull).executeUpdate();

        // G1 だけ宛て 30 件（T1 には判定後に 20 件を超えて出る）
        for (int i = 0; i < 30; i++) {
            insertFeed(null, List.of(g1.getId()), false, null, false);
        }
        // それより新しい G2＋未分類を 1 ページを超える件数。前絞りを通るが T1 は判定で落ちるため、
        // T1 は 1 ページ目でほとんど拾えず、キーセットで 2 ページ目へ読み進めてから 20 件に達する
        for (int i = 0; i < AnnouncementAudienceMatcher.PAGE_SIZE + 5; i++) {
            insertFeed(null, List.of(g2.getId()), true, null, i % 17 == 0);
        }
        flushAndClear();

        deleteGroup(g2.getId());
        moveTeamToGroup(t3.getId(), orgX.getId(), g1.getId());
    }

    @Test
    @DisplayName("T1〜T4 のそれぞれで、取得結果が「全候補を Matcher に通した先頭 20 件」と順序まで一致する")
    void pagedFetchEqualsMatcherOverAllCandidates() {
        for (Long teamId : List.of(t1.getId(), t2.getId(), t3.getId(), t4.getId())) {
            flushAndClear();
            List<AnnouncementFeedEntity> all = allCandidates();
            Set<Long> matched = matcher.matchingFeedIds(teamId, all);
            List<Long> expected = all.stream()
                    .filter(f -> matched.contains(f.getId()))
                    .map(AnnouncementFeedEntity::getId)
                    .limit(LIMIT)
                    .toList();

            flushAndClear();
            List<Long> actual = matcher.findVisibleOrgFeeds(teamId, ALL_VISIBILITIES, LIMIT).stream()
                    .map(AnnouncementFeedEntity::getId)
                    .toList();

            assertThat(actual).as("teamId=%d の取得結果は Matcher の判定（正）と一致する", teamId)
                    .containsExactlyElementsOf(expected);
        }
    }

    @Test
    @DisplayName("前提: 判定後の打ち切りとキーセットの読み進めが実際に起きる件数になっている")
    void fixtureExercisesPagingAndLimit() {
        List<AnnouncementFeedEntity> all = allCandidates();
        assertThat(all.size()).as("候補は 1 ページを超える").isGreaterThan(AnnouncementAudienceMatcher.PAGE_SIZE);
        assertThat(matcher.matchingFeedIds(t4.getId(), all)).as("T4（未分類）は判定後に 20 件を超える")
                .hasSizeGreaterThan(LIMIT);
        assertThat(matcher.matchingFeedIds(t1.getId(), all)).as("T1 は判定後に 20 件を超える")
                .hasSizeGreaterThan(LIMIT);
        List<AnnouncementFeedEntity> t1FirstPage = feedQueryRepository.findOrgScopePageForTeamDashboard(
                orgX.getId(), ALL_VISIBILITIES, t1.getId(), g1.getId(), null, AnnouncementAudienceMatcher.PAGE_SIZE);
        assertThat(t1FirstPage).as("T1 の 1 ページ目は満杯（続きがある）").hasSize(AnnouncementAudienceMatcher.PAGE_SIZE);
        assertThat(matcher.matchingFeedIds(t1.getId(), t1FirstPage))
                .as("T1 は 1 ページ目だけでは 20 件に届かない（キーセットで 2 ページ目へ読み進める）")
                .hasSizeLessThan(LIMIT);
    }

    @Test
    @DisplayName("キーセットで小さいページに分けて読んでも、1 回で読んだ結果と同じ行が同じ順に並ぶ（ピン留めの境界を含む）")
    void keysetPagesConcatenateToSingleRead() {
        for (Long teamId : List.of(t1.getId(), t2.getId(), t4.getId())) {
            UUID group = currentGroupOf(teamId);
            flushAndClear();
            List<Long> single = feedQueryRepository.findOrgScopePageForTeamDashboard(
                    orgX.getId(), ALL_VISIBILITIES, teamId, group, null, 10_000).stream()
                    .map(AnnouncementFeedEntity::getId).toList();

            List<Long> paged = new ArrayList<>();
            TeamDashboardFeedKey after = null;
            for (int guard = 0; guard < 10_000; guard++) {
                List<AnnouncementFeedEntity> page = feedQueryRepository.findOrgScopePageForTeamDashboard(
                        orgX.getId(), ALL_VISIBILITIES, teamId, group, after, 3);
                page.forEach(f -> paged.add(f.getId()));
                if (page.size() < 3) {
                    break;
                }
                after = TeamDashboardFeedKey.of(page.get(page.size() - 1));
            }

            assertThat(single).as("前提: 3 行のページを何枚もまたぐ").hasSizeGreaterThan(9);
            assertThat(paged).as("teamId=%d のキーセット読み", teamId).containsExactlyElementsOf(single);
        }
    }

    /** 組織X の全候補（宛先の判定前）を、取得と同じ並び（ピン留め優先・新着順・同時刻は id 降順）で返す。 */
    private List<AnnouncementFeedEntity> allCandidates() {
        return feedQueryRepository.findByScope(AnnouncementScopeType.ORGANIZATION, orgX.getId(),
                        ALL_VISIBILITIES, null, 10_000).stream()
                .sorted(Comparator.comparing((AnnouncementFeedEntity f) -> Boolean.TRUE.equals(f.getIsPinned()))
                        .reversed()
                        .thenComparing(AnnouncementFeedEntity::getCreatedAt, Comparator.reverseOrder())
                        .thenComparing(AnnouncementFeedEntity::getId, Comparator.reverseOrder()))
                .toList();
    }

    private UUID currentGroupOf(Long teamId) {
        return membershipRepository.findActiveOrgGroupsByTeamId(teamId).stream()
                .filter(p -> orgX.getId().equals(p.getOrganizationId()))
                .map(p -> p.getGroupId())
                .findFirst().orElse(null);
    }

    /** 3 件ずつ同じ時刻にして、同時刻の並び（id 降順）も検証の対象にする。 */
    private long insertFeed(String targetTeamIds, List<UUID> groups, boolean includeUnassigned,
                            String targetAudience, boolean pinned) {
        String groupJson = null;
        if (groups != null) {
            List<String> ids = new ArrayList<>();
            for (UUID g : groups) {
                ids.add("\"" + g + "\"");
            }
            groupJson = "[" + String.join(",", ids) + "]";
        }
        AnnouncementFeedEntity feed = feedRepository.saveAndFlush(AnnouncementFeedEntity.builder()
                .scopeType(AnnouncementScopeType.ORGANIZATION)
                .scopeId(orgX.getId())
                .sourceType(AnnouncementSourceType.BULLETIN_THREAD)
                .sourceId(1_000_000L + seq)
                .titleCache("整合試練の告知")
                .visibility("MEMBERS_AND_ABOVE")
                .targetTeamIds(targetTeamIds)
                .targetGroupIds(groupJson)
                .includeUnassigned(includeUnassigned)
                .targetAudience(targetAudience)
                .isPinned(pinned)
                .build());
        em.createNativeQuery("UPDATE announcement_feeds SET created_at = :c WHERE id = :id")
                .setParameter("c", BASE.plusMinutes(seq / 3)).setParameter("id", feed.getId()).executeUpdate();
        seq++;
        return feed.getId();
    }

    private void snapshot(long feedId, UUID groupId, Long teamId) {
        snapshotRepository.saveAndFlush(AnnouncementFeedGroupSnapshotEntity.builder()
                .feedId(feedId)
                .groupId(groupId.toString())
                .teamId(teamId)
                .build());
    }
}
