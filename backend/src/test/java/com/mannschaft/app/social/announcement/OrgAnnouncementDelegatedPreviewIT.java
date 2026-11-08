package com.mannschaft.app.social.announcement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.bulletin.TargetType;
import com.mannschaft.app.bulletin.entity.BulletinAttachmentEntity;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.PostType;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.acl.StorageAclEntity;
import com.mannschaft.app.common.storage.acl.StorageAclMode;
import com.mannschaft.app.common.storage.acl.StorageAclScopeType;
import com.mannschaft.app.common.storage.acl.StorageAclStatus;
import com.mannschaft.app.dashboard.dto.TeamDashboardResponse;
import com.mannschaft.app.dashboard.service.DashboardService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.teamgroup.entity.OrgTeamGroupEntity;
import com.mannschaft.app.payment.PaymentItemType;
import com.mannschaft.app.payment.entity.ContentPaymentGateEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-261011-0035 ALIC-1 試練: 組織告知の宛先チームの現役メンバーへの本文プレビュー委任（陣立て書 r2〜r6 確定版）。
 *
 * <p>人物（陣立て書 §4）: 組織 X に東グループ（T1・T4）と西グループ（T2）。他組織 Y と加盟チーム TY。
 * N=無所属、G=T1 GUEST（user_roles のみ）、S=T1 SUPPORTER、M=T1 MEMBER、A=T1 ADMIN（いずれも組織ロールなし）、
 * OS=X 直属 SUPPORTER、OM=X 直属 MEMBER、OSM=X SUPPORTER かつ T1 MEMBER。</p>
 *
 * <p>判定の記号: 「直」= 直属経路（sourceUrl 非 null）、「委」= 委任経路（FULL・sourceUrl=null・attachments=[]）、
 * 404 = ANNOUNCE_001。実 Security Filter・実 F00・実 MySQL を通し、認可・課金をモックしない
 * （C06 のみ課金結果 null を注入）。新設予定の型は参照せず、HTTP と既存の DashboardService だけで観測する。</p>
 *
 * <p>C 群（委任経路固有の拒否）は「先に委任経路で 200 が通ること（前提）」を確かめてから条件を変えて 404 を確かめる。
 * 前提を置かないと、委任経路が無い現状では拒否だけが空振りで緑になり、拒否の検証にならないため。</p>
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
@DisplayName("CMP-261011-0035 ALIC-1 組織告知プレビューの宛先チームへの委任")
class OrgAnnouncementDelegatedPreviewIT extends AbstractMySqlIntegrationTest {

    // AnnouncementPreviewScopeContractIT と同じ上書き構成にして TestContext を共有する
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
    @Autowired private DashboardService dashboardService;
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
    private Long authorY;
    private Map<String, Long> people;

    @BeforeEach
    void setUp() {
        String key = UUID.randomUUID().toString().substring(0, 8);
        orgX = TeamOrgFixtureHelper.insertOrganization(em, "委任の組織X", "alic-x-" + key);
        orgY = TeamOrgFixtureHelper.insertOrganization(em, "他組織Y", "alic-y-" + key);
        east = saveGroup(orgX, "東");
        west = saveGroup(orgX, "西");
        t1 = TeamOrgFixtureHelper.insertTeam(em, "T1", "alic-t1-" + key);
        t2 = TeamOrgFixtureHelper.insertTeam(em, "T2", "alic-t2-" + key);
        t4 = TeamOrgFixtureHelper.insertTeam(em, "T4", "alic-t4-" + key);
        ty = TeamOrgFixtureHelper.insertTeam(em, "TY", "alic-ty-" + key);
        affiliate(t1, orgX, east);
        affiliate(t4, orgX, east);
        affiliate(t2, orgX, west);
        affiliate(ty, orgY, null);

        author = saveUser("author-" + key);
        MembershipTestHelper.insertMembership(em, author, ScopeType.ORGANIZATION, orgX, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, author, "ADMIN", null, orgX);
        authorY = saveUser("author-y-" + key);
        MembershipTestHelper.insertMembership(em, authorY, ScopeType.ORGANIZATION, orgY, RoleKind.MEMBER);

        people = new LinkedHashMap<>();
        people.put("N", saveUser("n-" + key));
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
        Long osm = teamPerson("osm-" + key, t1, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, osm, ScopeType.ORGANIZATION, orgX, RoleKind.SUPPORTER);
        people.put("OSM", osm);
        people.put("T2M", teamPerson("t2m-" + key, t2, RoleKind.MEMBER));
        people.put("TYM", teamPerson("tym-" + key, ty, RoleKind.MEMBER));
        em.flush();
    }

    // ═════════════════════════ A 許否表（東宛て） ═════════════════════════

    /**
     * A01〜A06（東宛て。元は告知と同じ visibility）。期待値の記号: 404 / 200（経路を問わない）/ 直 / 委。
     * 列の順は N, G, S, M, A, OS, OSM。
     */
    @ParameterizedTest(name = "{0} 告知={1} 元={2}")
    @CsvSource({
            "AC-ALIC1-A01, PUBLIC, BLOG, 404, 200, 200, 200, 200, 直, 直",
            "AC-ALIC1-A02, PUBLIC, BULLETIN, 404, 404, 委, 委, 委, 直, 直",
            "AC-ALIC1-A03, SUPPORTERS, BLOG, 404, 404, 委, 委, 委, 直, 直",
            "AC-ALIC1-A04, SUPPORTERS, BULLETIN, 404, 404, 委, 委, 委, 直, 直",
            "AC-ALIC1-A05, MEMBERS, BLOG, 404, 404, 404, 委, 委, 404, 委",
            "AC-ALIC1-A06, MEMBERS, BULLETIN, 404, 404, 404, 委, 委, 404, 委",
    })
    @DisplayName("AC-ALIC1-A01〜A06 東宛て告知の許否表（直属経路・委任経路・404）")
    void 許否表(String ac, String feedVisibility, String source, String n, String g, String s, String m,
             String a, String os, String osm) throws Exception {
        AnnouncementFeedEntity feed = eastFeed(feedVisibility, source);
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("N", n);
        expected.put("G", g);
        expected.put("S", s);
        expected.put("M", m);
        expected.put("A", a);
        expected.put("OS", os);
        expected.put("OSM", osm);
        List<String> failures = new ArrayList<>();
        for (var e : expected.entrySet()) {
            String actual = observe(feed, people.get(e.getKey()));
            if (!matches(e.getValue(), actual)) {
                failures.add(e.getKey() + ": 期待=" + e.getValue() + " 実際=" + actual);
            }
        }
        assertThat(failures).as(ac + " の許否表の食い違い").isEmpty();
    }

    @Test
    @DisplayName("AC-ALIC1-A07 PUBLIC 全チーム宛て×PUBLIC 記事は全員 200（N・G は公開閲覧不変 M6-b の回帰）")
    void 全チーム宛てPUBLICは従来どおり全員200() throws Exception {
        AnnouncementFeedEntity feed = orgFeed("PUBLIC", "BLOG", null, null, false);
        for (String who : List.of("N", "G", "S", "M", "A", "OS", "OSM")) {
            assertThat(observe(feed, people.get(who))).as(who).isNotEqualTo("404");
        }
    }

    @Test
    @DisplayName("AC-ALIC1-A08 未ログインはすべて 401")
    void 未ログインは401() throws Exception {
        for (String[] c : new String[][] {{"PUBLIC", "BLOG"}, {"SUPPORTERS", "BULLETIN"}, {"MEMBERS", "BLOG"}}) {
            String path = path(eastFeed(c[0], c[1]));
            em.flush();
            mockMvc.perform(get(path).servletPath(path)).andExpect(status().isUnauthorized());
        }
    }

    // ═════════════════════════ B 経路 ═════════════════════════

    @Test
    @DisplayName("AC-ALIC1-B01 OSM: SUPPORTERS 告知→MEMBERS 記事は委任経路で 200（sourceUrl=null）")
    void OSMはSUPPORTERS告知のMEMBERS記事を委任で読む() throws Exception {
        BlogPostEntity post = saveOrgBlog(orgX, Visibility.MEMBERS_AND_ABOVE, "MEMBERS 記事本文");
        AnnouncementFeedEntity feed = saveFeed(builder(AnnouncementSourceType.BLOG_POST, post.getId(), "SUPPORTERS")
                .targetGroupIds(groups(east)).build());
        assertDelegated(feed, people.get("OSM"))
                .andExpect(jsonPath("$.data.blogPost.content.body").value("MEMBERS 記事本文"));
    }

    @Test
    @DisplayName("AC-ALIC1-B02 OSM: SUPPORTERS 告知→掲示板は直属経路で 200（添付一覧あり）（回帰）")
    void OSMは掲示板を直属で読み添付も返る() throws Exception {
        BulletinThreadEntity thread = saveOrgThread(orgX, author);
        saveOrgAttachment(thread, orgX);
        AnnouncementFeedEntity feed = saveFeed(builder(AnnouncementSourceType.BULLETIN_THREAD, thread.getId(),
                "SUPPORTERS").targetGroupIds(groups(east)).build());
        assertDirect(feed, people.get("OSM"))
                .andExpect(jsonPath("$.data.attachments.length()").value(1));
    }

    @Test
    @DisplayName("AC-ALIC1-B03 PUBLIC 告知→MEMBERS 記事: M は委任 200、S・G・N は 404")
    void PUBLIC告知のMEMBERS記事はMEMBERだけが委任で読む() throws Exception {
        BlogPostEntity post = saveOrgBlog(orgX, Visibility.MEMBERS_AND_ABOVE, "会員本文");
        AnnouncementFeedEntity feed = saveFeed(builder(AnnouncementSourceType.BLOG_POST, post.getId(), "PUBLIC")
                .targetGroupIds(groups(east)).build());
        assertDelegated(feed, people.get("M"));
        for (String who : List.of("S", "G", "N")) {
            assertHidden(feed, people.get(who));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRIVATE", "ADMINS_AND_ABOVE"})
    @DisplayName("AC-ALIC1-B04 PUBLIC 告知→PRIVATE/ADMINS_AND_ABOVE 記事は M・OSM・OM とも 404（委任で開かない・回帰）")
    void 管理者限定と非公開の記事は委任しない(String visibility) throws Exception {
        BlogPostEntity post = saveOrgBlog(orgX, Visibility.valueOf(visibility), "管理者限定本文");
        AnnouncementFeedEntity feed = saveFeed(builder(AnnouncementSourceType.BLOG_POST, post.getId(), "PUBLIC")
                .targetGroupIds(groups(east)).build());
        for (String who : List.of("M", "OSM", "OM")) {
            assertHidden(feed, people.get(who));
        }
    }

    @ParameterizedTest(name = "宛先モード={0} 告知={1} 元={2}")
    @CsvSource({
            "ALL, SUPPORTERS, BLOG", "ALL, SUPPORTERS, BULLETIN", "ALL, MEMBERS, BLOG", "ALL, MEMBERS, BULLETIN",
            "TEAMS, SUPPORTERS, BLOG", "TEAMS, SUPPORTERS, BULLETIN", "TEAMS, MEMBERS, BLOG", "TEAMS, MEMBERS, BULLETIN",
            "GROUP, SUPPORTERS, BLOG", "GROUP, SUPPORTERS, BULLETIN", "GROUP, MEMBERS, BLOG", "GROUP, MEMBERS, BULLETIN",
    })
    @DisplayName("AC-ALIC1-B05 宛先モード（全チーム／チームを選ぶ[T1]／グループ）で A03〜A06 の M の結果（委任 200）は不変")
    void 宛先モードによらずMは委任で読む(String mode, String feedVisibility, String source) throws Exception {
        AnnouncementFeedEntity feed = switch (mode) {
            case "ALL" -> orgFeed(feedVisibility, source, null, null, false);
            case "TEAMS" -> orgFeed(feedVisibility, source, "[" + t1 + "]", null, false);
            default -> orgFeed(feedVisibility, source, null, groups(east), false);
        };
        assertDelegated(feed, people.get("M"));
    }

    @Test
    @DisplayName("AC-ALIC1-B06 T1 SUPPORTER＋T4 MEMBER は MEMBERS を読める。宛先外 T2 のロールは数えない")
    void 宛先チームのロールの最大で判定し宛先外チームは数えない() throws Exception {
        String key = UUID.randomUUID().toString().substring(0, 8);
        Long s1m4 = teamPerson("s1m4-" + key, t1, RoleKind.SUPPORTER);
        MembershipTestHelper.insertMembership(em, s1m4, ScopeType.TEAM, t4, RoleKind.MEMBER);
        Long s1m2 = teamPerson("s1m2-" + key, t1, RoleKind.SUPPORTER);
        MembershipTestHelper.insertMembership(em, s1m2, ScopeType.TEAM, t2, RoleKind.MEMBER);
        AnnouncementFeedEntity blog = eastFeed("MEMBERS", "BLOG");
        AnnouncementFeedEntity thread = eastFeed("MEMBERS", "BULLETIN");
        assertDelegated(blog, s1m4);
        assertDelegated(thread, s1m4);
        assertHidden(blog, s1m2);
        assertHidden(thread, s1m2);
    }

    // ═════════════════════════ C 委任経路固有の拒否 ═════════════════════════

    @Test
    @DisplayName("AC-ALIC1-C01 告知の所有スコープと元のスコープ不一致は委任経路でも 404")
    void 元のスコープ不一致は404() throws Exception {
        assertDelegated(eastFeed("MEMBERS", "BLOG"), people.get("M"));
        BlogPostEntity foreign = saveBlog(null, orgY, authorY, Visibility.MEMBERS_AND_ABOVE, "他組織の本文");
        AnnouncementFeedEntity mismatch = saveFeed(builder(AnnouncementSourceType.BLOG_POST, foreign.getId(),
                "MEMBERS").targetGroupIds(groups(east)).build());
        assertHidden(mismatch, people.get("M"));
        BulletinThreadEntity foreignThread = saveOrgThread(orgY, authorY);
        AnnouncementFeedEntity mismatchThread = saveFeed(builder(AnnouncementSourceType.BULLETIN_THREAD,
                foreignThread.getId(), "MEMBERS").targetGroupIds(groups(east)).build());
        assertHidden(mismatchThread, people.get("M"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BLOG", "BULLETIN"})
    @DisplayName("AC-ALIC1-C02 組織が準備中（PROVISIONED）なら委任経路でも 404")
    void 準備中組織は404(String source) throws Exception {
        AnnouncementFeedEntity feed = eastFeed("MEMBERS", source);
        assertDelegated(feed, people.get("M"));
        em.createNativeQuery("UPDATE organizations SET lifecycle_status = 'PROVISIONED' WHERE id = :id")
                .setParameter("id", orgX).executeUpdate();
        flushAndClear();
        assertHidden(feed, people.get("M"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BLOG", "BULLETIN"})
    @DisplayName("AC-ALIC1-C03 組織が非アクティブ（論理削除）なら委任経路でも 404")
    void 非アクティブ組織は404(String source) throws Exception {
        AnnouncementFeedEntity feed = eastFeed("MEMBERS", source);
        assertDelegated(feed, people.get("M"));
        em.createNativeQuery("UPDATE organizations SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", orgX).executeUpdate();
        flushAndClear();
        assertHidden(feed, people.get("M"));
    }

    @Test
    @DisplayName("AC-ALIC1-C04 元ブログだけ LOCKED（CUSTOM 未購入）は委任経路でも LOCKED・本文/ID/URL は null")
    void 元ブログLOCKEDは委任でもLOCKED() throws Exception {
        BlogPostEntity custom = saveOrgBlog(orgX, Visibility.CUSTOM, "購入者だけの本文");
        addOrgGate("POST", custom.getId(), false);
        AnnouncementFeedEntity feed = saveFeed(builder(AnnouncementSourceType.BLOG_POST, custom.getId(), "MEMBERS")
                .targetGroupIds(groups(east)).build());
        preview(feed, people.get("M")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessState").value("LOCKED"))
                .andExpect(jsonPath("$.data.sourceId").value(nullValue()))
                .andExpect(jsonPath("$.data.sourceUrl").value(nullValue()))
                .andExpect(jsonPath("$.data.blogPost").value(nullValue()))
                .andExpect(jsonPath("$.data.bulletinThread").value(nullValue()))
                .andExpect(jsonPath("$.data.attachments").isEmpty());
    }

    @Test
    @DisplayName("AC-ALIC1-C05 元ブログだけ HIDDEN は委任経路でも 404")
    void 元ブログHIDDENは委任でも404() throws Exception {
        BlogPostEntity post = saveOrgBlog(orgX, Visibility.MEMBERS_AND_ABOVE, "隠す本文");
        AnnouncementFeedEntity feed = saveFeed(builder(AnnouncementSourceType.BLOG_POST, post.getId(), "MEMBERS")
                .targetGroupIds(groups(east)).build());
        assertDelegated(feed, people.get("M"));
        addOrgGate("POST", post.getId(), true);
        assertHidden(feed, people.get("M"));
    }

    @Test
    @DisplayName("AC-ALIC1-C06 委任経路の元ブログの strict 課金結果 null は 500（404 に丸めない）")
    void 元ブログの課金nullは500() throws Exception {
        BlogPostEntity post = saveOrgBlog(orgX, Visibility.MEMBERS_AND_ABOVE, "課金障害の本文");
        AnnouncementFeedEntity feed = saveFeed(builder(AnnouncementSourceType.BLOG_POST, post.getId(), "MEMBERS")
                .targetGroupIds(groups(east)).build());
        assertDelegated(feed, people.get("M"));
        org.mockito.Mockito.doReturn(null).when(previewPaymentGateService).checkAccessForPreview(
                org.mockito.ArgumentMatchers.eq("POST"), org.mockito.ArgumentMatchers.eq(post.getId()),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        preview(feed, people.get("M")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("COMMON_999"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("AC-ALIC1-C06 委任経路の元ブログの strict 課金が例外なら 500（404 に丸めない）")
    void 元ブログの課金例外は500() throws Exception {
        BlogPostEntity post = saveOrgBlog(orgX, Visibility.MEMBERS_AND_ABOVE, "課金障害の本文");
        AnnouncementFeedEntity feed = saveFeed(builder(AnnouncementSourceType.BLOG_POST, post.getId(), "MEMBERS")
                .targetGroupIds(groups(east)).build());
        assertDelegated(feed, people.get("M"));
        org.mockito.Mockito.doThrow(new IllegalStateException("試練の課金障害")).when(previewPaymentGateService)
                .checkAccessForPreview(org.mockito.ArgumentMatchers.eq("POST"),
                        org.mockito.ArgumentMatchers.eq(post.getId()),
                        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        preview(feed, people.get("M")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("COMMON_999"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BLOG_DELETED", "BLOG_DRAFT", "BLOG_PRIVATE", "THREAD_DELETED"})
    @DisplayName("AC-ALIC1-C07 記事の削除・下書き化・PRIVATE 化、スレッド削除の後は委任経路でも 404")
    void 元の状態変化は委任でも404(String change) throws Exception {
        boolean thread = change.startsWith("THREAD");
        BlogPostEntity post = thread ? null : saveOrgBlog(orgX, Visibility.MEMBERS_AND_ABOVE, "状態変化の本文");
        BulletinThreadEntity th = thread ? saveOrgThread(orgX, author) : null;
        AnnouncementFeedEntity feed = saveFeed(builder(thread ? AnnouncementSourceType.BULLETIN_THREAD
                        : AnnouncementSourceType.BLOG_POST, thread ? th.getId() : post.getId(), "MEMBERS")
                .targetGroupIds(groups(east)).build());
        assertDelegated(feed, people.get("M"));
        switch (change) {
            case "BLOG_DELETED" -> post.softDelete();
            case "BLOG_DRAFT" -> post.unpublish(LocalDateTime.now(wallClock));
            case "BLOG_PRIVATE" -> post.update(post.getTitle(), post.getSlug(), post.getBody(), null, null,
                    Visibility.PRIVATE, post.getPriority(), (short) 0);
            default -> th.softDelete();
        }
        flushAndClear();
        assertHidden(feed, people.get("M"));
    }

    @Test
    @DisplayName("AC-ALIC1-C08 告知の課金 LOCKED は委任でも LOCKED、チームダッシュボードの access_state も LOCKED（チーム ADMIN も素通ししない M2）")
    void 告知LOCKEDは委任でもLOCKEDでチームADMINも素通ししない() throws Exception {
        AnnouncementFeedEntity feed = eastFeed("MEMBERS", "BLOG");
        addOrgGate("ANNOUNCEMENT", feed.getId(), false);
        for (String who : List.of("M", "A")) {
            preview(feed, people.get(who)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.accessState").value("LOCKED"))
                    .andExpect(jsonPath("$.data.sourceId").value(nullValue()))
                    .andExpect(jsonPath("$.data.sourceUrl").value(nullValue()))
                    .andExpect(jsonPath("$.data.blogPost").value(nullValue()));
            Map<String, Object> item = teamNotice(people.get(who), t1, feed.getId())
                    .orElseThrow(() -> new AssertionError(who + " のチームダッシュボードに告知が出ていない"));
            assertThat(item.get("access_state")).as(who + " の一覧 access_state").isEqualTo("LOCKED");
            assertThat(item.get("excerpt_cache")).as(who + " LOCKED は要約を伏せる").isNull();
        }
    }

    @Test
    @DisplayName("AC-ALIC1-C09 委任資格の流用なし: M が組織掲示板スレッド・ブログ詳細（slug）・添付 URL 発行 API を直接呼ぶと従来どおり 403/404")
    void 委任資格を通常APIへ流用しない() throws Exception {
        BulletinThreadEntity thread = saveOrgThread(orgX, author);
        BulletinAttachmentEntity attachment = saveOrgAttachment(thread, orgX);
        BlogPostEntity post = saveOrgBlog(orgX, Visibility.MEMBERS_AND_ABOVE, "直接は読めない本文");
        AnnouncementFeedEntity threadFeed = saveFeed(builder(AnnouncementSourceType.BULLETIN_THREAD, thread.getId(),
                "MEMBERS").targetGroupIds(groups(east)).build());
        AnnouncementFeedEntity blogFeed = saveFeed(builder(AnnouncementSourceType.BLOG_POST, post.getId(),
                "MEMBERS").targetGroupIds(groups(east)).build());
        // 前提: 告知プレビューの委任経路では読める
        assertDelegated(threadFeed, people.get("M"));
        assertDelegated(blogFeed, people.get("M"));
        Long m = people.get("M");
        for (String path : List.of(
                "/api/v1/organizations/" + orgX + "/bulletin/threads/" + thread.getId(),
                "/api/v1/bulletin/threads/" + thread.getId(),
                "/api/v1/bulletin/threads/" + thread.getId() + "/attachments",
                "/api/v1/bulletin/attachments/" + attachment.getId() + "/download-url",
                "/api/v1/blog/posts/" + post.getSlug() + "?organizationId=" + orgX)) {
            String servletPath = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
            int code = mockMvc.perform(get(path).servletPath(servletPath).with(user(m.toString())))
                    .andReturn().getResponse().getStatus();
            assertThat(code).as(path + " は委任資格で開かない").isIn(403, 404);
        }
    }

    @Test
    @DisplayName("AC-ALIC1-C10 存在オラクル: 不在・宛先外・他テナント・期限切れ・元削除・visibility 不足・C01〜C03 の拒否は応答の形まで同一（回帰）")
    void 拒否の応答は理由によらず同一() throws Exception {
        Long m = people.get("M");
        Map<String, JsonNode> bodies = new LinkedHashMap<>();
        AnnouncementFeedEntity base = eastFeed("MEMBERS", "BLOG");
        bodies.put("不在", rejectBody("/api/v1/organizations/" + orgX + "/announcements/" + (base.getId() + 100000)
                + "/preview", m));
        bodies.put("宛先外", rejectBody(path(eastFeed("MEMBERS", "BLOG")), people.get("T2M")));
        AnnouncementFeedEntity foreignFeed = saveFeed(AnnouncementFeedEntity.builder()
                .scopeType(AnnouncementScopeType.ORGANIZATION).scopeId(orgY)
                .sourceType(AnnouncementSourceType.BLOG_POST)
                .sourceId(saveBlog(null, orgY, authorY, Visibility.MEMBERS_AND_ABOVE, "Y 本文").getId())
                .authorId(authorY).titleCache("Y の告知").visibility(AnnouncementVisibility.MEMBERS_AND_ABOVE)
                .build());
        bodies.put("他テナント", rejectBody(path(foreignFeed), m));
        bodies.put("他テナントIDを自組織パスで", rejectBody("/api/v1/organizations/" + orgX + "/announcements/"
                + foreignFeed.getId() + "/preview", m));
        AnnouncementFeedEntity expired = saveFeed(builder(AnnouncementSourceType.BLOG_POST,
                saveOrgBlog(orgX, Visibility.MEMBERS_AND_ABOVE, "期限切れ").getId(), "MEMBERS")
                .targetGroupIds(groups(east)).expiresAt(LocalDateTime.now(wallClock).minusDays(1)).build());
        bodies.put("期限切れ", rejectBody(path(expired), m));
        BlogPostEntity deleted = saveOrgBlog(orgX, Visibility.MEMBERS_AND_ABOVE, "削除");
        AnnouncementFeedEntity deletedFeed = saveFeed(builder(AnnouncementSourceType.BLOG_POST, deleted.getId(),
                "MEMBERS").targetGroupIds(groups(east)).build());
        deleted.softDelete();
        bodies.put("元削除", rejectBody(path(deletedFeed), m));
        bodies.put("visibility不足", rejectBody(path(eastFeed("MEMBERS", "BLOG")), people.get("S")));
        AnnouncementFeedEntity mismatch = saveFeed(builder(AnnouncementSourceType.BLOG_POST,
                saveBlog(null, orgY, authorY, Visibility.MEMBERS_AND_ABOVE, "不一致").getId(), "MEMBERS")
                .targetGroupIds(groups(east)).build());
        bodies.put("C01スコープ不一致", rejectBody(path(mismatch), m));
        String target = path(eastFeed("MEMBERS", "BULLETIN"));
        em.createNativeQuery("UPDATE organizations SET lifecycle_status = 'PROVISIONED' WHERE id = :id")
                .setParameter("id", orgX).executeUpdate();
        flushAndClear();
        bodies.put("C02準備中", rejectBody(target, m));
        em.createNativeQuery("UPDATE organizations SET lifecycle_status = 'ACTIVE', deleted_at = NOW() WHERE id = :id")
                .setParameter("id", orgX).executeUpdate();
        flushAndClear();
        bodies.put("C03非アクティブ", rejectBody(target, m));

        JsonNode reference = bodies.get("不在");
        assertThat(reference.path("error").path("code").asText()).isEqualTo("ANNOUNCE_001");
        bodies.forEach((reason, body) -> assertThat(body).as(reason + " の応答本文が不在と同一").isEqualTo(reference));
    }

    @Test
    @DisplayName("AC-ALIC1-C11 宛先外 T2 MEMBER・TY MEMBER は A03〜A06 で 404・一覧にも出ず、他パスで開いても 404（回帰）")
    void 宛先外と他テナントは404で一覧にも出ない() throws Exception {
        List<AnnouncementFeedEntity> feeds = List.of(eastFeed("SUPPORTERS", "BLOG"), eastFeed("SUPPORTERS", "BULLETIN"),
                eastFeed("MEMBERS", "BLOG"), eastFeed("MEMBERS", "BULLETIN"));
        for (AnnouncementFeedEntity feed : feeds) {
            assertHidden(feed, people.get("T2M"));
            assertHidden(feed, people.get("TYM"));
            assertThat(teamNotice(people.get("T2M"), t2, feed.getId())).as("T2 の一覧").isEmpty();
            assertThat(teamNotice(people.get("TYM"), ty, feed.getId())).as("TY の一覧").isEmpty();
            for (String other : List.of("/api/v1/organizations/" + orgY + "/announcements/" + feed.getId() + "/preview",
                    "/api/v1/teams/" + t1 + "/announcements/" + feed.getId() + "/preview")) {
                mockMvc.perform(get(other).servletPath(other).with(user(people.get("M").toString())))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.error.code").value("ANNOUNCE_001"));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"MEMBER_LEFT", "TEAM_LEFT_ORG", "TEAM_ARCHIVED", "TEAM_DELETED", "TEAM_MOVED_TO_WEST"})
    @DisplayName("AC-ALIC1-C12 離脱・チームの組織離脱・アーカイブ・論理削除・グループ移動の直後は一覧から消えプレビュー 404（M3）")
    void 所属変化の直後は404で一覧からも消える(String change) throws Exception {
        Long m = people.get("M");
        AnnouncementFeedEntity feed = eastFeed("MEMBERS", "BULLETIN");
        assertDelegated(feed, m);
        assertThat(teamNotice(m, t1, feed.getId())).as("前提: T1 の一覧に出ている").isPresent();
        switch (change) {
            case "MEMBER_LEFT" -> em.createNativeQuery("UPDATE memberships SET left_at = NOW() "
                            + "WHERE user_id = :u AND scope_type = 'TEAM' AND scope_id = :t")
                    .setParameter("u", m).setParameter("t", t1).executeUpdate();
            case "TEAM_LEFT_ORG" -> em.createNativeQuery(
                            "DELETE FROM team_org_memberships WHERE team_id = :t AND organization_id = :o")
                    .setParameter("t", t1).setParameter("o", orgX).executeUpdate();
            case "TEAM_ARCHIVED" -> em.createNativeQuery("UPDATE teams SET archived_at = NOW() WHERE id = :t")
                    .setParameter("t", t1).executeUpdate();
            case "TEAM_DELETED" -> em.createNativeQuery("UPDATE teams SET deleted_at = NOW() WHERE id = :t")
                    .setParameter("t", t1).executeUpdate();
            default -> moveTeamToGroup(t1, west);
        }
        flushAndClear();
        assertHidden(feed, m);
        assertThat(teamNotice(m, t1, feed.getId())).as(change + " の後は T1 の一覧から消える").isEmpty();
    }

    @Test
    @DisplayName("AC-ALIC1-C13 宛先グループ削除後でも、スナップショットに T1 があれば M は委任で 200")
    void 削除グループはスナップショットで委任() throws Exception {
        UUID doomed = saveGroup(orgX, "消える");
        moveTeamToGroup(t1, doomed);
        AnnouncementFeedEntity feed = orgFeed("MEMBERS", "BULLETIN", null, groups(doomed), false);
        em.persist(AnnouncementFeedGroupSnapshotEntity.builder().feedId(feed.getId())
                .groupId(doomed.toString()).teamId(t1).build());
        em.find(OrgTeamGroupEntity.class, doomed).softDelete(wallClock.instant(), author);
        flushAndClear();
        assertDelegated(feed, people.get("M"));
    }

    // ═════════════════════════ I 会員限定の元を持つ告知を GUEST の一覧から外す（M11 代案） ═════════════════════════

    @Test
    @DisplayName("AC-ALIC1-I01 東宛て PUBLIC 告知×掲示板: G はチームダッシュボード 403（露出なし）・プレビュー 404、S・M には出て読める")
    void 掲示板元のPUBLIC告知はGUESTに露出しない() throws Exception {
        // 裏取り（裁可）: G は user_roles が GUEST のみで memberships 行がなく、チームダッシュボードの入口
        // （checkMembership）で 403。チームの告知一覧 API はチームスコープの告知だけを返し組織告知を載せない。
        // よって GUEST の「一覧」は存在せず、会員限定の元（掲示板）の告知が GUEST に露出する経路がない。
        AnnouncementFeedEntity feed = eastFeed("PUBLIC", "BULLETIN");
        Long g = people.get("G");
        assertGuestDashboardDenied(g);
        assertTeamFeedApiExcludesOrgFeed(g, feed.getId());
        assertHidden(feed, g);
        for (String who : List.of("S", "M")) {
            assertThat(teamNotice(people.get(who), t1, feed.getId())).as(who + " の一覧に出る").isPresent();
            assertDelegated(feed, people.get(who));
        }
    }

    @Test
    @DisplayName("AC-ALIC1-I02 G はチームダッシュボード 403。プレビューは PUBLIC ブログ元なら読め、MEMBERS ブログ元なら読めない")
    void GUESTはPUBLICブログ元だけプレビューで読める() throws Exception {
        Long g = people.get("G");
        AnnouncementFeedEntity open = eastFeed("PUBLIC", "BLOG");
        BlogPostEntity members = saveOrgBlog(orgX, Visibility.MEMBERS_AND_ABOVE, "会員向け");
        AnnouncementFeedEntity closed = saveFeed(builder(AnnouncementSourceType.BLOG_POST, members.getId(), "PUBLIC")
                .targetGroupIds(groups(east)).build());
        assertGuestDashboardDenied(g);
        preview(open, g).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.blogPost.content.body", notNullValue()));
        assertTeamFeedApiExcludesOrgFeed(g, open.getId());
        assertTeamFeedApiExcludesOrgFeed(g, closed.getId());
        assertHidden(closed, g);
    }

    // ═════════════════════════ 観測ヘルパー ═════════════════════════

    /** プレビューの結果を「404」「直」「委」「200（LOCKED など本文以外）」「その他 HTTP 状態」に要約する。 */
    private String observe(AnnouncementFeedEntity feed, Long viewer) throws Exception {
        MvcResult result = preview(feed, viewer).andReturn();
        int code = result.getResponse().getStatus();
        if (code == 404) {
            JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
            return "ANNOUNCE_001".equals(body.path("error").path("code").asText()) ? "404" : "404(" + body + ")";
        }
        if (code != 200) {
            return String.valueOf(code);
        }
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        if (!"FULL".equals(data.path("accessState").asText())) {
            return "200(" + data.path("accessState").asText() + ")";
        }
        boolean hasUrl = !data.path("sourceUrl").isNull() && !data.path("sourceUrl").isMissingNode();
        if (hasUrl) {
            return "直";
        }
        return data.path("attachments").isArray() && data.path("attachments").isEmpty() ? "委" : "委(添付あり)";
    }

    private static boolean matches(String expected, String actual) {
        if ("200".equals(expected)) {
            return "直".equals(actual) || "委".equals(actual);
        }
        return expected.equals(actual);
    }

    private ResultActions assertDelegated(AnnouncementFeedEntity feed, Long viewer) throws Exception {
        return preview(feed, viewer).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessState").value("FULL"))
                .andExpect(jsonPath("$.data.sourceUrl").value(nullValue()))
                .andExpect(jsonPath("$.data.attachments").isEmpty());
    }

    private ResultActions assertDirect(AnnouncementFeedEntity feed, Long viewer) throws Exception {
        return preview(feed, viewer).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessState").value("FULL"))
                .andExpect(jsonPath("$.data.sourceUrl", notNullValue()));
    }

    private void assertHidden(AnnouncementFeedEntity feed, Long viewer) throws Exception {
        preview(feed, viewer).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ANNOUNCE_001"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private JsonNode rejectBody(String path, Long viewer) throws Exception {
        em.flush();
        MvcResult result = mockMvc.perform(get(path).servletPath(path).with(user(viewer.toString())))
                .andExpect(status().isNotFound()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private ResultActions preview(AnnouncementFeedEntity feed, Long viewer) throws Exception {
        em.flush();
        String path = path(feed);
        return mockMvc.perform(get(path).servletPath(path).with(user(viewer.toString())));
    }

    private static String path(AnnouncementFeedEntity feed) {
        String scope = feed.getScopeType() == AnnouncementScopeType.TEAM ? "teams" : "organizations";
        return "/api/v1/" + scope + "/" + feed.getScopeId() + "/announcements/" + feed.getId() + "/preview";
    }

    /**
     * チームダッシュボード（実経路 DashboardService）の告知一覧から feedId の項目を探す。
     * ダッシュボード入口で拒否（COMMON_002 等）される閲覧者には一覧そのものが無いので empty を返す。
     */
    private Optional<Map<String, Object>> teamNotice(Long viewer, Long teamId, Long feedId) {
        em.flush();
        em.clear();
        TeamDashboardResponse response;
        try {
            response = dashboardService.getTeamDashboard(viewer, teamId, "WEEK");
        } catch (BusinessException denied) {
            return Optional.empty();
        }
        if (response.getTeamNotices() == null) {
            return Optional.empty();
        }
        return response.getTeamNotices().stream()
                .filter(item -> ((Number) item.get("id")).longValue() == feedId)
                .findFirst();
    }

    /**
     * G（user_roles の GUEST のみ・memberships なし）はチームダッシュボードの入口で 403（COMMON_002）になる。
     * 一覧そのものが存在しないので、会員限定の元の告知が G の一覧に露出することはない。
     */
    private void assertGuestDashboardDenied(Long g) {
        em.flush();
        em.clear();
        assertThatThrownBy(() -> dashboardService.getTeamDashboard(g, t1, "WEEK"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode()).isEqualTo("COMMON_002"));
    }

    /** チームの告知一覧 API（未読件数 meta.unreadCount を含む）に組織告知が載らないこと。 */
    private void assertTeamFeedApiExcludesOrgFeed(Long viewer, Long feedId) throws Exception {
        em.flush();
        String path = "/api/v1/teams/" + t1 + "/announcements";
        String json = mockMvc.perform(get(path).servletPath(path).with(user(viewer.toString())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(json);
        List<Long> ids = new ArrayList<>();
        root.path("data").forEach(item -> ids.add(item.path("id").asLong()));
        assertThat(ids).as("チームの告知一覧 API").doesNotContain(feedId);
        assertThat(root.path("meta").path("unreadCount").asLong()).as("未読件数に数えない").isZero();
    }

    // ═════════════════════════ フィクスチャ ═════════════════════════

    private AnnouncementFeedEntity eastFeed(String visibility, String source) {
        return orgFeed(visibility, source, null, groups(east), false);
    }

    /** 組織 X の告知（元は告知と同じ visibility の組織記事、または組織掲示板）。 */
    private AnnouncementFeedEntity orgFeed(String visibility, String source, String teams, String groups,
                                           boolean includeUnassigned) {
        Long sourceId;
        AnnouncementSourceType type;
        if ("BULLETIN".equals(source)) {
            type = AnnouncementSourceType.BULLETIN_THREAD;
            sourceId = saveOrgThread(orgX, author).getId();
        } else {
            type = AnnouncementSourceType.BLOG_POST;
            sourceId = saveOrgBlog(orgX, blogVisibility(visibility), visibility + " の記事本文").getId();
        }
        return saveFeed(builder(type, sourceId, visibility).targetTeamIds(teams).targetGroupIds(groups)
                .includeUnassigned(includeUnassigned).build());
    }

    private static Visibility blogVisibility(String feedVisibility) {
        return switch (feedVisibility) {
            case "PUBLIC" -> Visibility.PUBLIC;
            case "SUPPORTERS" -> Visibility.SUPPORTERS_AND_ABOVE;
            default -> Visibility.MEMBERS_AND_ABOVE;
        };
    }

    private static String feedVisibility(String label) {
        return switch (label) {
            case "PUBLIC" -> AnnouncementVisibility.PUBLIC;
            case "SUPPORTERS" -> AnnouncementVisibility.SUPPORTERS_AND_ABOVE;
            default -> AnnouncementVisibility.MEMBERS_AND_ABOVE;
        };
    }

    private AnnouncementFeedEntity.AnnouncementFeedEntityBuilder<?, ?> builder(AnnouncementSourceType type, Long sourceId,
                                                                        String visibilityLabel) {
        return AnnouncementFeedEntity.builder().scopeType(AnnouncementScopeType.ORGANIZATION).scopeId(orgX)
                .sourceType(type).sourceId(sourceId).authorId(author).titleCache("委任の試練の告知")
                .excerptCache("告知の要約").visibility(feedVisibility(visibilityLabel));
    }

    private static String groups(UUID... ids) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(ids[i]).append('"');
        }
        return sb.append(']').toString();
    }

    private AnnouncementFeedEntity saveFeed(AnnouncementFeedEntity feed) {
        em.persist(feed);
        return feed;
    }

    private BlogPostEntity saveOrgBlog(Long orgId, Visibility visibility, String body) {
        return saveBlog(null, orgId, author, visibility, body);
    }

    private BlogPostEntity saveBlog(Long teamId, Long orgId, Long writer, Visibility visibility, String body) {
        BlogPostEntity post = BlogPostEntity.builder().teamId(teamId).organizationId(orgId).authorId(writer)
                .title("委任の試練の記事").slug("alic-" + UUID.randomUUID()).body(body).visibility(visibility)
                .status(PostStatus.PUBLISHED).postType(PostType.BLOG).build();
        em.persist(post);
        return post;
    }

    private BulletinThreadEntity saveOrgThread(Long orgId, Long writer) {
        BulletinThreadEntity thread = BulletinThreadEntity.builder()
                .scopeType(com.mannschaft.app.bulletin.ScopeType.ORGANIZATION).scopeId(orgId)
                .authorId(writer).title("委任の試練の掲示板").body("掲示板の本文").build();
        em.persist(thread);
        return thread;
    }

    private BulletinAttachmentEntity saveOrgAttachment(BulletinThreadEntity thread, Long orgId) {
        String fileKey = "alic/" + UUID.randomUUID();
        BulletinAttachmentEntity attachment = BulletinAttachmentEntity.builder()
                .targetType(TargetType.THREAD).targetId(thread.getId()).fileKey(fileKey)
                .originalFilename("試練.pdf").fileSize(20L).contentType("application/pdf").createdBy(author).build();
        em.persist(attachment);
        em.persist(StorageAclEntity.builder().fileKey(fileKey).ownerId(author)
                .scopeType(StorageAclScopeType.ORGANIZATION).scopeKey(orgId.toString())
                .aclMode(StorageAclMode.CONTENT_BOUND).status(StorageAclStatus.CLAIMED)
                .contentType("application/pdf").parentContentReferenceType("BULLETIN_THREAD")
                .parentContentReferenceKey(thread.getId().toString())
                .attachmentBindingType("BULLETIN_ATTACHMENT")
                .attachmentBindingKey(attachment.getId().toString())
                .expiresAt(wallClock.instant().plusSeconds(3600)).build());
        return attachment;
    }

    private void addOrgGate(String contentType, Long contentId, boolean titleHidden) {
        PaymentItemEntity item = PaymentItemEntity.builder().organizationId(orgX).name("組織の本文購読")
                .type(PaymentItemType.MONTHLY_FEE).amount(BigDecimal.valueOf(100)).build();
        em.persist(item);
        em.persist(ContentPaymentGateEntity.builder().paymentItemId(item.getId()).contentType(contentType)
                .contentId(contentId).isTitleHidden(titleHidden).createdBy(author).build());
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

    private int moveTeamToGroup(Long teamId, UUID groupId) {
        em.flush();
        return em.createQuery("UPDATE TeamOrgMembershipEntity m SET m.groupId = :g "
                        + "WHERE m.teamId = :t AND m.organizationId = :o")
                .setParameter("g", groupId).setParameter("t", teamId).setParameter("o", orgX).executeUpdate();
    }

    private Long teamPerson(String name, Long teamId, RoleKind kind) {
        Long id = saveUser(name);
        MembershipTestHelper.insertMembership(em, id, ScopeType.TEAM, teamId, kind);
        return id;
    }

    private Long saveUser(String name) {
        UserEntity entity = UserEntity.builder().email(name + "@alic-preview.example.com")
                .lastName("試練").firstName("利用者").displayName(name).isSearchable(true)
                .locale("ja").timezone("Asia/Tokyo").status(UserEntity.UserStatus.ACTIVE).build();
        em.persist(entity);
        return entity.getId();
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }
}
