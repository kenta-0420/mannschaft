package com.mannschaft.app.social.announcement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.PostType;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.teamgroup.entity.OrgTeamGroupEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.support.test.TeamOrgFixtureHelper;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-261011-0035 組織告知一覧の宛先資格絞り込み（一覧と本文の可否を一致させる）。
 *
 * <p>組織 X に東グループ（T1・T4）と西グループ（T2）。他組織 Y。人物は {@link OrgAnnouncementDelegatedPreviewIT} と同じ。
 * 元コンテンツは告知と同じ visibility の組織ブログ（A01 の形）に揃え、「一覧に出る ⇔ プレビューが 404 でない」を
 * 全人物×全告知で突き合わせる。</p>
 */
@AutoConfigureMockMvc
@Transactional
@org.springframework.test.context.TestPropertySource(properties = {
        "mannschaft.storage.endpoint=http://127.0.0.1:19002",
        "mannschaft.storage.access-key=preview-it-key",
        "mannschaft.storage.secret-key=preview-it-signing-secret",
        "mannschaft.storage.bucket=preview-it"
})
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261011-0035 組織告知一覧は宛先資格で絞る（一覧と本文の可否を一致）")
class OrgAnnouncementFeedAudienceIT extends AbstractMySqlIntegrationTest {

    // OrgAnnouncementDelegatedPreviewIT と同じ上書き構成にして TestContext を共有する
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.mannschaft.app.payment.service.PaymentGateService previewPaymentGateService;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private AnnouncementPreviewSourceService previewSourceService;

    @TestBean(name = "wallClock", methodName = "fixedWallClock")
    private Clock wallClock;

    static Clock fixedWallClock() {
        return Clock.fixed(Instant.parse("2026-10-04T03:00:00Z"), ZoneId.of("Asia/Tokyo"));
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @PersistenceContext private EntityManager em;

    private Long orgX;
    private Long orgY;
    private UUID east;
    private UUID west;
    private Long t1;
    private Long t2;
    private Long t4;
    private Long ty;
    private Long author;
    private Map<String, Long> people;
    private String key;

    @BeforeEach
    void setUp() {
        key = UUID.randomUUID().toString().substring(0, 8);
        orgX = TeamOrgFixtureHelper.insertOrganization(em, "一覧の組織X", "feed-x-" + key);
        orgY = TeamOrgFixtureHelper.insertOrganization(em, "一覧の他組織Y", "feed-y-" + key);
        east = saveGroup(orgX, "東");
        west = saveGroup(orgX, "西");
        t1 = TeamOrgFixtureHelper.insertTeam(em, "T1", "feed-t1-" + key);
        t2 = TeamOrgFixtureHelper.insertTeam(em, "T2", "feed-t2-" + key);
        t4 = TeamOrgFixtureHelper.insertTeam(em, "T4", "feed-t4-" + key);
        ty = TeamOrgFixtureHelper.insertTeam(em, "TY", "feed-ty-" + key);
        affiliate(t1, orgX, east);
        affiliate(t4, orgX, east);
        affiliate(t2, orgX, west);
        affiliate(ty, orgY, null);

        author = saveUser("author-" + key);
        MembershipTestHelper.insertMembership(em, author, ScopeType.ORGANIZATION, orgX, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, author, "ADMIN", null, orgX);

        people = new LinkedHashMap<>();
        people.put("N", saveUser("n-" + key));
        Long yAdmin = saveUser("ya-" + key);
        MembershipTestHelper.insertMembership(em, yAdmin, ScopeType.ORGANIZATION, orgY, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, yAdmin, "ADMIN", null, orgY);
        people.put("YA", yAdmin);
        Long g = saveUser("g-" + key);
        MembershipTestHelper.insertUserRole(em, g, "GUEST", t1, null);
        people.put("G", g);
        people.put("S", teamPerson("s-" + key, t1, RoleKind.SUPPORTER));
        people.put("M", teamPerson("m-" + key, t1, RoleKind.MEMBER));
        Long a = teamPerson("a-" + key, t1, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, a, "ADMIN", t1, null);
        people.put("A", a);
        Long os = saveUser("os-" + key);
        MembershipTestHelper.insertMembership(em, os, ScopeType.ORGANIZATION, orgX, RoleKind.SUPPORTER);
        people.put("OS", os);
        Long om = saveUser("om-" + key);
        MembershipTestHelper.insertMembership(em, om, ScopeType.ORGANIZATION, orgX, RoleKind.MEMBER);
        people.put("OM", om);
        people.put("T2M", teamPerson("t2m-" + key, t2, RoleKind.MEMBER));
        people.put("TYM", teamPerson("tym-" + key, ty, RoleKind.MEMBER));
        em.flush();
    }

    @Test
    @DisplayName("東宛て PUBLIC×PUBLIC 記事: 他組織管理者・無所属・宛先外チームの者の一覧に出ず、プレビューも 404（一致）。宛先チーム・直属の者には出る")
    void 東宛てPUBLICは宛先外の一覧に出ない() throws Exception {
        AnnouncementFeedEntity feed = feed("PUBLIC", null, groups(east), false);
        for (String outsider : List.of("N", "YA", "T2M", "TYM")) {
            assertThat(listedIds(outsider)).as(outsider + " の一覧").doesNotContain(feed.getId());
            assertThat(previewStatus(feed, outsider)).as(outsider + " のプレビュー").isEqualTo(404);
        }
        for (String insider : List.of("G", "S", "M", "A", "OS", "OM")) {
            assertThat(listedIds(insider)).as(insider + " の一覧").contains(feed.getId());
            assertThat(previewStatus(feed, insider)).as(insider + " のプレビュー").isEqualTo(200);
        }
    }

    @Test
    @DisplayName("宛先チーム T1 の管理者（組織ロールなし）には会員限定の東宛て告知が一覧に出る（委任資格）。宛先外 T2 の者には出ない")
    void 宛先チームの管理者は会員限定も一覧に出る() throws Exception {
        AnnouncementFeedEntity feed = feed("MEMBERS", null, groups(east), false);
        assertThat(listedIds("A")).contains(feed.getId());
        assertThat(previewStatus(feed, "A")).isEqualTo(200);
        assertThat(listedIds("T2M")).doesNotContain(feed.getId());
        assertThat(previewStatus(feed, "T2M")).isEqualTo(404);
        assertThat(listedIds("OM")).contains(feed.getId());
    }

    @Test
    @DisplayName("回帰: 全チーム宛て・宛先指定なしの PUBLIC は無所属にも一覧に出てプレビュー 200。会員限定は非メンバーに出ない")
    void 従来の公開と会員限定は不変() throws Exception {
        AnnouncementFeedEntity open = feed("PUBLIC", null, null, false);
        AnnouncementFeedEntity members = feed("MEMBERS", null, null, false);
        for (String who : List.of("N", "YA", "TYM")) {
            assertThat(listedIds(who)).as(who).contains(open.getId()).doesNotContain(members.getId());
            assertThat(previewStatus(open, who)).isEqualTo(200);
            assertThat(previewStatus(members, who)).isEqualTo(404);
        }
        assertThat(listedIds("OM")).contains(open.getId(), members.getId());
    }

    @Test
    @DisplayName("全人物×全告知で『一覧に出る ⇔ プレビューが 404 でない』が一致し、未読件数も一覧と同じ集合")
    void 一覧とプレビューの可否が全員で一致する() throws Exception {
        List<AnnouncementFeedEntity> feeds = new ArrayList<>();
        for (String visibility : List.of("PUBLIC", "SUPPORTERS", "MEMBERS")) {
            feeds.add(feed(visibility, null, null, false));
            feeds.add(feed(visibility, null, groups(east), false));
            feeds.add(feed(visibility, null, groups(west), false));
            feeds.add(feed(visibility, "[" + t1 + "]", null, false));
        }
        List<String> mismatches = new ArrayList<>();
        for (String who : people.keySet()) {
            Set<Long> listed = new LinkedHashSet<>(listedIds(who));
            for (AnnouncementFeedEntity f : feeds) {
                boolean readable = previewStatus(f, who) != 404;
                if (readable != listed.contains(f.getId())) {
                    mismatches.add(who + " feed=" + f.getId() + " vis=" + f.getVisibility()
                            + " 一覧=" + listed.contains(f.getId()) + " プレビュー可=" + readable);
                }
            }
            JsonNode body = list(who);
            assertThat(body.path("meta").path("unreadCount").asLong()).as(who + " の未読件数")
                    .isEqualTo(body.path("data").size());
        }
        assertThat(mismatches).as("一覧とプレビューの可否の食い違い").isEmpty();
    }

    @Test
    @DisplayName("一覧の SQL 本数は告知 1 件と 20 件で差がない")
    void 一覧の本数は件数に比例しない() throws Exception {
        feed("PUBLIC", null, groups(east), false);
        list("M"); // ウォームアップ
        int one = countList("M");
        for (int i = 0; i < 19; i++) {
            feed("PUBLIC", null, groups(east), false);
        }
        int twenty = countList("M");
        assertThat(twenty).as("1件: %d 本 / 20件: %d 本", one, twenty).isEqualTo(one);
    }

    private int countList(String who) throws Exception {
        em.flush();
        em.clear();
        SqlIntentCounter.reset();
        JsonNode body = list(who);
        int count = SqlIntentCounter.totalCount();
        assertThat(body.path("data").size()).isPositive();
        return count;
    }

    // ═════════════════════════ 観測 ═════════════════════════

    private JsonNode list(String who) throws Exception {
        em.flush();
        String path = "/api/v1/organizations/" + orgX + "/announcements";
        String json = mockMvc.perform(get(path).servletPath(path).param("limit", "50")
                        .with(user(people.get(who).toString())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private List<Long> listedIds(String who) throws Exception {
        List<Long> ids = new ArrayList<>();
        list(who).path("data").forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
    }

    private int previewStatus(AnnouncementFeedEntity feed, String who) throws Exception {
        em.flush();
        String path = "/api/v1/organizations/" + orgX + "/announcements/" + feed.getId() + "/preview";
        return mockMvc.perform(get(path).servletPath(path).with(user(people.get(who).toString())))
                .andReturn().getResponse().getStatus();
    }

    // ═════════════════════════ フィクスチャ ═════════════════════════

    /** 組織 X の告知（元は告知と同じ visibility の組織ブログ）。 */
    private AnnouncementFeedEntity feed(String visibility, String teams, String groups, boolean includeUnassigned) {
        Visibility blogVisibility = switch (visibility) {
            case "PUBLIC" -> Visibility.PUBLIC;
            case "SUPPORTERS" -> Visibility.SUPPORTERS_AND_ABOVE;
            default -> Visibility.MEMBERS_AND_ABOVE;
        };
        String feedVisibility = switch (visibility) {
            case "PUBLIC" -> AnnouncementVisibility.PUBLIC;
            case "SUPPORTERS" -> AnnouncementVisibility.SUPPORTERS_AND_ABOVE;
            default -> AnnouncementVisibility.MEMBERS_AND_ABOVE;
        };
        BlogPostEntity post = BlogPostEntity.builder().organizationId(orgX).authorId(author)
                .title("一覧の試練の記事").slug("feed-" + UUID.randomUUID()).body(visibility + " の本文")
                .visibility(blogVisibility).status(PostStatus.PUBLISHED).postType(PostType.BLOG).build();
        em.persist(post);
        AnnouncementFeedEntity feed = AnnouncementFeedEntity.builder().scopeType(AnnouncementScopeType.ORGANIZATION)
                .scopeId(orgX).sourceType(AnnouncementSourceType.BLOG_POST).sourceId(post.getId()).authorId(author)
                .titleCache("一覧の試練の告知").excerptCache("告知の要約").visibility(feedVisibility)
                .targetTeamIds(teams).targetGroupIds(groups).includeUnassigned(includeUnassigned).build();
        em.persist(feed);
        return feed;
    }

    private static String groups(UUID... ids) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(ids[i]).append('"');
        }
        return sb.append(']').toString();
    }

    private UUID saveGroup(Long orgId, String name) {
        OrgTeamGroupEntity group = OrgTeamGroupEntity.builder().organizationId(orgId)
                .name(name + UUID.randomUUID().toString().substring(0, 8)).build();
        em.persist(group);
        return group.getId();
    }

    private void affiliate(Long teamId, Long orgId, UUID groupId) {
        em.persist(TeamOrgMembershipEntity.builder().teamId(teamId).organizationId(orgId)
                .status(TeamOrgMembershipEntity.Status.ACTIVE).groupId(groupId)
                .invitedAt(LocalDateTime.now(wallClock).minusDays(2))
                .respondedAt(LocalDateTime.now(wallClock).minusDays(1)).build());
    }

    private Long teamPerson(String name, Long teamId, RoleKind kind) {
        Long id = saveUser(name);
        MembershipTestHelper.insertMembership(em, id, ScopeType.TEAM, teamId, kind);
        return id;
    }

    private Long saveUser(String name) {
        UserEntity entity = UserEntity.builder().email(name + "@feed-audience.example.com")
                .lastName("試練").firstName("利用者").displayName(name).isSearchable(true)
                .locale("ja").timezone("Asia/Tokyo").status(UserEntity.UserStatus.ACTIVE).build();
        em.persist(entity);
        return entity.getId();
    }
}
