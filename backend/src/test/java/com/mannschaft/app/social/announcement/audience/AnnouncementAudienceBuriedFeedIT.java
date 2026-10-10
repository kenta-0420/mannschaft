package com.mannschaft.app.social.announcement.audience;

import com.mannschaft.app.dashboard.dto.TeamDashboardResponse;
import com.mannschaft.app.dashboard.service.DashboardService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.social.announcement.AnnouncementFeedEntity;
import com.mannschaft.app.social.announcement.AnnouncementFeedRepository;
import com.mannschaft.app.social.announcement.AnnouncementScopeType;
import com.mannschaft.app.social.announcement.AnnouncementSourceType;
import com.mannschaft.app.support.test.MembershipTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F01.2.1 部隊 6-C — 他グループ宛ての新しい告知が大量にあっても、その後ろの自グループ宛ての告知がチームの
 * ダッシュボードに出ること（Codex 検分 中: 上位 20 件で切ってから宛先を判定していた取りこぼしの回帰防止）。
 *
 * <p><b>トランザクションを持たない本番経路で観測する</b>。{@code DashboardService} はクラス単位の tx を外してあり、
 * 本番では各読み取りが呼び出し先の tx で走る。テストの tx の中で呼ぶと、その経路（tx の外で Entity を受け取る・
 * 呼び出しごとに別の永続化コンテキストで読む）を検証できないため、本クラスはテストに tx を付けない。
 * フィクスチャは {@link TransactionTemplate} で確定（commit）させ、{@link #tearDown} で自分が作った行だけを消す。</p>
 *
 * <p>他グループ宛ての告知は、DB 側の前絞りで落ちる「G2 だけ宛て」と、前絞りを通って Java の判定で落ちる
 * 「G2＋未分類宛て」（T1 は生存グループ G1 に所属するので未分類の条件に当たらない）を、どちらも 1 ページ
 * （{@link AnnouncementAudienceMatcher#PAGE_SIZE} 行）を超える件数だけ置く。後者により、キーセットで
 * 2 ページ目以降へ読み進める経路も通る。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 6-C 他グループ宛ての新しい告知の後ろにある自グループ宛ての告知もダッシュボードに出る（tx なし）")
class AnnouncementAudienceBuriedFeedIT extends AbstractBroadcastAudienceIT {

    private static final Long VIEWER_G1 = 940603041L;
    private static final Long VIEWER_G2 = 940603042L;

    /** DB 側の前絞りで落ちる「G2 だけ宛て」の件数（上位 20 件・1 ページの両方を超える）。 */
    private static final int G2_ONLY = AnnouncementAudienceMatcher.PAGE_SIZE + 10;

    /** 前絞りを通り Java の判定で落ちる「G2＋未分類宛て」の件数（1 ページを超え、2 ページ目へ読み進めさせる）。 */
    private static final int G2_WITH_UNASSIGNED = AnnouncementAudienceMatcher.PAGE_SIZE + 10;

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 9, 1, 9, 0);

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private AnnouncementFeedRepository feedRepository;

    private TransactionTemplate tx;
    private Set<String> rolesBefore;
    private Long orgId;
    private UUID g1Id;
    private UUID g2Id;
    private Long t1Id;
    private Long t2Id;
    private long ownFeedId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        rolesBefore = tx.execute(s -> roleNames());
        tx.executeWithoutResult(s -> {
            orgId = newOrg(true).getId();
            g1Id = newGroup(orgId, "G1", 0).getId();
            g2Id = newGroup(orgId, "G2", 1).getId();
            t1Id = activeTeam(orgId, g1Id, "T1").getId();
            t2Id = activeTeam(orgId, g2Id, "T2").getId();
            seedViewer(VIEWER_G1, orgId, t1Id);
            seedViewer(VIEWER_G2, orgId, t2Id);
            em.flush();
        });
        tx.executeWithoutResult(s -> {
            // 自グループ（G1）宛ての告知がいちばん古い
            ownFeedId = insertFeed(List.of(g1Id), false, BASE);
            for (int i = 0; i < G2_ONLY; i++) {
                insertFeed(List.of(g2Id), false, BASE.plusMinutes(1 + i));
            }
            for (int i = 0; i < G2_WITH_UNASSIGNED; i++) {
                insertFeed(List.of(g2Id), true, BASE.plusMinutes(1 + G2_ONLY + i));
            }
            em.flush();
        });
    }

    @AfterEach
    void tearDown() {
        if (orgId == null || t1Id == null || t2Id == null) {
            // 準備の途中で失敗した（原因は setUp の例外として既に報告されている）。消す対象が確定していない
            return;
        }
        tx.executeWithoutResult(s -> {
            exec("DELETE FROM announcement_feed_group_snapshots WHERE feed_id IN "
                    + "(SELECT id FROM (SELECT id FROM announcement_feeds "
                    + "WHERE scope_type = 'ORGANIZATION' AND scope_id = " + orgId + ") f)");
            exec("DELETE FROM announcement_feeds WHERE scope_type = 'ORGANIZATION' AND scope_id = " + orgId);
            exec("DELETE FROM team_org_memberships WHERE organization_id = " + orgId);
            exec("DELETE FROM org_team_groups WHERE organization_id = " + orgId);
            exec("DELETE FROM memberships WHERE user_id IN (" + VIEWER_G1 + ", " + VIEWER_G2 + ")");
            exec("DELETE FROM teams WHERE id IN (" + t1Id + ", " + t2Id + ")");
            exec("DELETE FROM organizations WHERE id = " + orgId);
            exec("DELETE FROM users WHERE id IN (" + VIEWER_G1 + ", " + VIEWER_G2 + ")");
            for (String role : roleNames()) {
                if (!rolesBefore.contains(role)) {
                    em.createNativeQuery("DELETE FROM roles WHERE name = :n").setParameter("n", role).executeUpdate();
                }
            }
        });
    }

    @Test
    @DisplayName("他グループ宛ての新しい告知が 1 ページを超えて積まれても、T1 には自グループ宛ての告知が出て、T2 には出ない")
    void ownGroupFeedBuriedUnderOtherGroupFeedsIsShown() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .as("前提: 本番と同じく tx の外からダッシュボードを呼ぶ").isFalse();

        List<Long> seenByT1 = teamNoticeIds(VIEWER_G1, t1Id);
        List<Long> seenByT2 = teamNoticeIds(VIEWER_G2, t2Id);

        assertThat(seenByT1).as("自グループ（G1）宛ての告知は、より新しい他グループ宛てが %d 件あっても出る",
                G2_ONLY + G2_WITH_UNASSIGNED).containsExactly(ownFeedId);
        assertThat(seenByT2).as("対照: G2 のチームには G1 宛ては出ず、G2 宛ての新しいものが出る")
                .doesNotContain(ownFeedId).isNotEmpty();
    }

    private List<Long> teamNoticeIds(Long viewer, Long teamId) {
        TeamDashboardResponse response = dashboardService.getTeamDashboard(viewer, teamId, "WEEK");
        assertThat(response.getTeamNotices()).as("チームのお知らせウィジェットが見えている").isNotNull();
        return response.getTeamNotices().stream()
                .map(m -> ((Number) m.get("id")).longValue())
                .toList();
    }

    private void seedViewer(Long userId, Long organizationId, Long teamId) {
        MembershipTestHelper.insertActiveUser(em, userId);
        MembershipTestHelper.insertMembership(em, userId, ScopeType.ORGANIZATION, organizationId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, userId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
    }

    private long insertFeed(List<UUID> groups, boolean includeUnassigned, LocalDateTime createdAt) {
        List<String> ids = new ArrayList<>();
        for (UUID g : groups) {
            ids.add("\"" + g + "\"");
        }
        AnnouncementFeedEntity feed = feedRepository.saveAndFlush(AnnouncementFeedEntity.builder()
                .scopeType(AnnouncementScopeType.ORGANIZATION)
                .scopeId(orgId)
                .sourceType(AnnouncementSourceType.BULLETIN_THREAD)
                .sourceId(createdAt.getMinute() + 1L)
                .titleCache("埋もれ試練の告知")
                .visibility("MEMBERS_AND_ABOVE")
                .targetGroupIds("[" + String.join(",", ids) + "]")
                .includeUnassigned(includeUnassigned)
                .build());
        em.createNativeQuery("UPDATE announcement_feeds SET created_at = :c WHERE id = :id")
                .setParameter("c", createdAt).setParameter("id", feed.getId()).executeUpdate();
        return feed.getId();
    }

    @SuppressWarnings("unchecked")
    private Set<String> roleNames() {
        return new HashSet<>((List<String>) em.createNativeQuery("SELECT name FROM roles").getResultList());
    }

    private void exec(String sql) {
        em.createNativeQuery(sql).executeUpdate();
    }
}
