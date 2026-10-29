package com.mannschaft.app.social.announcement;

import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.PostType;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.dashboard.service.RoleResolver;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.payment.service.PaymentGateService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.support.test.TeamOrgFixtureHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ALIC-1 既読（CMP-261011-0035 試練）: 宛先チームの現役メンバーへの単件既読の委任と、
 * 新設 read-batch（POST /api/v1/organizations/{orgId}/announcements/read-batch）の契約。
 *
 * <p>人物: 組織 X（T1・T2 が ACTIVE 加盟）、組織 Y（TY が ACTIVE 加盟）。
 * M = T1 MEMBER（組織ロールなし）、M2 = T2 MEMBER、N = 無所属、MY = TY MEMBER。
 * X の告知は {@code target_team_ids=[T1]}・MEMBERS_AND_ABOVE。</p>
 *
 * <p>出陣前の挙動: 単件既読は組織ロール（M は PUBLIC）で判定するため M も 404、
 * read-batch は EP が無い。よって D01〜D05 は red。D08（直属範囲の不変）は回帰として green。</p>
 *
 * <p>出陣への前提: read-batch の応答は既存 read-all と同じ {@code ApiResponse} 包み（{@code $.data.markedCount}）、
 * 400 は {@code $.error.code=COMMON_001}。課金評価は新設 {@code PaymentGateService#checkAccessBatchForPreview}、
 * 役割解決は {@code RoleResolver#resolveViewerRolesFresh}（または {@code resolveViewerRoleForPreview}）、
 * 書き込みは {@code AnnouncementReadStatusRepository#insertReadStatusesIgnoringExisting}。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("ALIC-1 既読: 宛先チームへの単件既読委任と組織 read-batch（CMP-261011-0035）")
class OrgAnnouncementReadBatchIT extends AbstractMySqlIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @PersistenceContext private EntityManager em;

    @MockitoSpyBean private PaymentGateService paymentGateService;
    @MockitoSpyBean private RoleResolver roleResolver;

    private Long orgX;
    private Long orgY;
    private Long t1;
    private Long t2;
    private Long ty;
    private Long memberM;
    private Long memberM2;
    private Long outsiderN;
    private Long memberMY;
    private Long authorId;
    private Long feedT1a;
    private Long feedT1b;
    private Long feedT2;
    private Long feedY;
    private Long missingId;

    @BeforeEach
    void setUp() {
        String key = UUID.randomUUID().toString().substring(0, 8);
        orgX = TeamOrgFixtureHelper.insertOrganization(em, "既読組織X", "alic-rb-x-" + key);
        orgY = TeamOrgFixtureHelper.insertOrganization(em, "既読組織Y", "alic-rb-y-" + key);
        t1 = TeamOrgFixtureHelper.insertTeam(em, "東T1", "alic-rb-t1-" + key);
        t2 = TeamOrgFixtureHelper.insertTeam(em, "西T2", "alic-rb-t2-" + key);
        ty = TeamOrgFixtureHelper.insertTeam(em, "他TY", "alic-rb-ty-" + key);
        LocalDateTime joined = LocalDateTime.now().minusDays(10);
        TeamOrgFixtureHelper.insertTeamOrgMembership(em, t1, orgX, "ACTIVE", joined);
        TeamOrgFixtureHelper.insertTeamOrgMembership(em, t2, orgX, "ACTIVE", joined);
        TeamOrgFixtureHelper.insertTeamOrgMembership(em, ty, orgY, "ACTIVE", joined);

        memberM = insertUser("m-" + key);
        memberM2 = insertUser("m2-" + key);
        outsiderN = insertUser("n-" + key);
        memberMY = insertUser("my-" + key);
        authorId = insertUser("author-" + key);
        MembershipTestHelper.insertMembership(em, memberM, ScopeType.TEAM, t1, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberM2, ScopeType.TEAM, t2, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberMY, ScopeType.TEAM, ty, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, authorId, ScopeType.ORGANIZATION, orgX, RoleKind.MEMBER);

        feedT1a = orgFeed(orgX, "[" + t1 + "]", AnnouncementVisibility.MEMBERS_AND_ABOVE);
        feedT1b = orgFeed(orgX, "[" + t1 + "]", AnnouncementVisibility.MEMBERS_AND_ABOVE);
        feedT2 = orgFeed(orgX, "[" + t2 + "]", AnnouncementVisibility.MEMBERS_AND_ABOVE);
        feedY = orgFeed(orgY, "[" + ty + "]", AnnouncementVisibility.MEMBERS_AND_ABOVE);
        em.flush();
        missingId = ((Number) em.createNativeQuery("SELECT COALESCE(MAX(id), 0) + 100000 FROM announcement_feeds")
                .getSingleResult()).longValue();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── D01 単件既読の委任 ──────────────────────────────────────────

    @Test
    @DisplayName("AC-ALIC1-D01 宛先チーム T1 の MEMBER は組織ロールなしでも組織告知を単件既読にできる")
    void D01_宛先チームメンバーの単件既読が成功する() throws Exception {
        singleRead(orgX, feedT1a, memberM).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isRead").value(true));
        em.flush();
        assertThat(countRead(feedT1a, memberM)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"M2", "N", "MY"})
    @DisplayName("AC-ALIC1-D01 宛先外 T2 MEMBER・無所属 N・他組織 TY MEMBER は 404 ANNOUNCE_001・既読行0（回帰）")
    void D01_宛先外の単件既読は404(String who) throws Exception {
        Long viewer = switch (who) {
            case "M2" -> memberM2;
            case "N" -> outsiderN;
            default -> memberMY;
        };
        singleRead(orgX, feedT1a, viewer).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ANNOUNCE_001"));
        em.flush();
        assertThat(countRead(feedT1a, viewer)).isZero();
    }

    // ── D02 read-batch の件数と冪等 ────────────────────────────────

    @Test
    @DisplayName("AC-ALIC1-D02 宛先内2・宛先外1・他組織1・存在しない1・重複1 → markedCount=2、宛先内2件だけ行")
    void D02_有効なIDだけ既読にして件数だけ返す() throws Exception {
        String body = readBatch(orgX, memberM, List.of(feedT1a, feedT1b, feedT2, feedY, missingId, feedT1a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.markedCount").value(2))
                .andReturn().getResponse().getContentAsString();
        java.util.Map<String, Object> data = com.jayway.jsonpath.JsonPath.read(body, "$.data");
        assertThat(data.keySet()).as("応答は件数のみ（どの ID が無効か返さない）").containsExactly("markedCount");
        em.flush();
        assertThat(readFeedIds(memberM)).containsExactlyInAnyOrder(feedT1a, feedT1b);
    }

    @Test
    @DisplayName("AC-ALIC1-D02 再送は markedCount=0・行数も read_at も不変、既読済み ID は0件と数える")
    void D02_再送と既読済みは0件() throws Exception {
        readBatch(orgX, memberM, List.of(feedT1a)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.markedCount").value(1));
        em.flush();
        Object readAtBefore = readAt(feedT1a, memberM);

        readBatch(orgX, memberM, List.of(feedT1a)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.markedCount").value(0));
        readBatch(orgX, memberM, List.of(feedT1a, feedT1b)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.markedCount").value(1));
        em.flush();
        assertThat(readFeedIds(memberM)).containsExactlyInAnyOrder(feedT1a, feedT1b);
        assertThat(readAt(feedT1a, memberM)).isEqualTo(readAtBefore);
    }

    // ── D03 入力検証 ──────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "not-json",
            "{}",
            "{\"feedIds\":null}",
            "{\"feedIds\":[]}",
            "{\"feedIds\":[FEED,null]}",
            "{\"feedIds\":[FEED,0]}",
            "{\"feedIds\":[FEED,-1]}",
            "{\"feedIds\":[FEED,\"abc\"]}",
            "{\"feedIds\":\"FEED\"}"
    })
    @DisplayName("AC-ALIC1-D03 body なし・非 JSON・feedIds なし/null/空・null 要素・0 以下・非数値は 400 COMMON_001・書き込みなし")
    void D03_不正入力は400(String bodyTemplate) throws Exception {
        String body = bodyTemplate.replace("FEED", String.valueOf(feedT1a));
        setAuth(memberM);
        String path = batchPath(orgX);
        var request = post(path).servletPath(path).contentType(MediaType.APPLICATION_JSON);
        if (!body.isEmpty()) {
            request = request.content(body);
        }
        mockMvc.perform(request).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        em.flush();
        assertThat(countRead(feedT1a, memberM)).isZero();
    }

    @Test
    @DisplayName("AC-ALIC1-D03 51件は 400 COMMON_001・書き込みなし、ちょうど50件は成功")
    void D03_上限50件() throws Exception {
        List<Long> fiftyOne = new ArrayList<>();
        fiftyOne.add(feedT1a);
        for (int i = 1; i <= 50; i++) {
            fiftyOne.add(missingId + i);
        }
        readBatch(orgX, memberM, fiftyOne).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        em.flush();
        assertThat(countRead(feedT1a, memberM)).isZero();

        List<Long> fifty = new ArrayList<>(fiftyOne.subList(0, 50));
        readBatch(orgX, memberM, fifty).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.markedCount").value(1));
        em.flush();
        assertThat(countRead(feedT1a, memberM)).isEqualTo(1);
    }

    // ── D04 障害は 500・行0 ───────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"NULL", "THROW"})
    @DisplayName("AC-ALIC1-D04 一括課金評価の null/例外は 500・既読行0（HIDDEN に丸めない）")
    void D04_課金評価障害は500(String mode) throws Exception {
        Answer<Object> answer = "NULL".equals(mode)
                ? invocation -> null
                : invocation -> { throw new IllegalStateException("試練: 一括課金評価障害"); };
        stubByName(paymentGateService, "checkAccessBatchForPreview", answer);
        readBatch(orgX, memberM, List.of(feedT1a, feedT1b)).andExpect(status().isInternalServerError());
        em.flush();
        assertThat(readFeedIds(memberM)).isEmpty();
    }

    @Test
    @DisplayName("AC-ALIC1-D04 役割解決の失敗は 500・既読行0")
    void D04_役割解決失敗は500() throws Exception {
        IllegalStateException failure = new IllegalStateException("試練: 役割解決障害");
        // 失敗を注入するのは、キャッシュなしの役割解決の2入口（read-batch はキャッシュ付きの resolveViewerRole を使わない。
        // 告知の可視性は降格・離脱の直後に効かせるためキャッシュを見ない＝ALIC-1 §2.6）。
        // @Cacheable の入口（resolveViewerRole）にスパイ経由で stub を掛けると、プロキシ越しにキャッシュ機構を通り
        // 意図しない値がキャッシュされうる（null キャッシュの罠）ため、この試験では掛けない。
        Mockito.doThrow(failure).when(roleResolver)
                .resolveViewerRolesFresh(ArgumentMatchers.any(), ArgumentMatchers.any());
        Mockito.doThrow(failure).when(roleResolver).resolveViewerRoleForPreview(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
        readBatch(orgX, memberM, List.of(feedT1a, feedT1b)).andExpect(status().isInternalServerError());
        em.flush();
        assertThat(readFeedIds(memberM)).isEmpty();
    }

    @Test
    @DisplayName("AC-ALIC1-D04 組織 ADMIN でも課金 HIDDEN の告知は read-batch で既読にならない（markedCount・行なし）")
    void D04_管理者でも課金HIDDENは既読にならない() throws Exception {
        Long orgAdmin = insertUser("admin-" + UUID.randomUUID().toString().substring(0, 8));
        MembershipTestHelper.insertMembership(em, orgAdmin, ScopeType.ORGANIZATION, orgX, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, orgAdmin, "ADMIN", null, orgX);
        // feedT1a は課金 HIDDEN、feedT1b は閲覧可
        Mockito.doAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(1);
            java.util.Map<Long, com.mannschaft.app.payment.dto.GateCheckResponse> result = new java.util.HashMap<>();
            for (Long id : ids) {
                boolean hidden = id.equals(feedT1a);
                result.put(id, new com.mannschaft.app.payment.dto.GateCheckResponse(!hidden, hidden, List.of()));
            }
            return result;
        }).when(paymentGateService).checkAccessBatchForPreview(
                ArgumentMatchers.any(), ArgumentMatchers.anyList(), ArgumentMatchers.any(), ArgumentMatchers.anyMap());

        readBatch(orgX, orgAdmin, List.of(feedT1a)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.markedCount").value(0));
        em.flush();
        assertThat(readFeedIds(orgAdmin)).as("HIDDEN は管理者でも既読行を作らない").isEmpty();

        readBatch(orgX, orgAdmin, List.of(feedT1a, feedT1b)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.markedCount").value(1));
        em.flush();
        assertThat(readFeedIds(orgAdmin)).containsExactly(feedT1b);
    }

    // ── D05 資格喪失後は行なし ────────────────────────────────────

    @Test
    @DisplayName("AC-ALIC1-D05 M の T1 離脱後の read-batch は markedCount=0・行なし")
    void D05_チーム離脱後は既読にならない() throws Exception {
        // 離脱前は読めることを先に確かめる（離脱の効果で 0 になったことを示すため）
        readBatch(orgX, memberM, List.of(feedT1a)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.markedCount").value(1));
        em.createNativeQuery("UPDATE memberships SET left_at = NOW() WHERE user_id = :u AND scope_type = 'TEAM' "
                + "AND scope_id = :t").setParameter("u", memberM).setParameter("t", t1).executeUpdate();
        em.flush();
        em.clear();
        readBatch(orgX, memberM, List.of(feedT1b)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.markedCount").value(0));
        em.flush();
        assertThat(countRead(feedT1b, memberM)).isZero();
    }

    @Test
    @DisplayName("AC-ALIC1-D05 T1 の組織離脱後の read-batch は markedCount=0・行なし")
    void D05_チームの組織離脱後は既読にならない() throws Exception {
        readBatch(orgX, memberM, List.of(feedT1a)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.markedCount").value(1));
        em.createNativeQuery("DELETE FROM team_org_memberships WHERE team_id = :t AND organization_id = :o")
                .setParameter("t", t1).setParameter("o", orgX).executeUpdate();
        em.flush();
        em.clear();
        readBatch(orgX, memberM, List.of(feedT1b)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.markedCount").value(0));
        em.flush();
        assertThat(countRead(feedT1b, memberM)).isZero();
    }

    // ── D08 直属範囲の不変（回帰） ────────────────────────────────

    @Test
    @DisplayName("AC-ALIC1-D08 組織お知らせ一覧・未読件数・組織 read-all は直属範囲のまま（M に MEMBERS 告知は出ず既読にもならない・回帰）")
    void D08_組織一覧と未読件数とreadAllは直属範囲のまま() throws Exception {
        // 全チーム宛て（宛先指定なし）の PUBLIC 告知は直属範囲でも非メンバーに見える（従来どおり）
        Long publicFeed = orgFeed(orgX, null, AnnouncementVisibility.PUBLIC);
        em.flush();
        setAuth(memberM);
        String listPath = "/api/v1/organizations/" + orgX + "/announcements";
        String body = mockMvc.perform(get(listPath).servletPath(listPath).param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.unreadCount").value(1))
                .andReturn().getResponse().getContentAsString();
        List<Long> listed = com.jayway.jsonpath.JsonPath.<List<Number>>read(body, "$.data[*].id").stream()
                .map(Number::longValue).toList();
        assertThat(listed).contains(publicFeed).doesNotContain(feedT1a, feedT1b, feedT2);

        String readAllPath = "/api/v1/organizations/" + orgX + "/announcements/read-all";
        mockMvc.perform(post(readAllPath).servletPath(readAllPath)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.markedCount").value(1))
                .andExpect(jsonPath("$.data.hasMoreUnread").value(false));
        em.flush();
        assertThat(readFeedIds(memberM)).containsExactly(publicFeed);
    }

    // ── ヘルパー ─────────────────────────────────────────────────

    /**
     * 出陣で新設されるメソッドを名前で stub する（試練時点ではコンパイル可能に保つため）。
     * メソッドが無ければ「出陣で新設する」旨で失敗させる（＝red の理由を明示）。
     */
    static void stubByName(Object spy, String methodName, Answer<?> answer) throws Exception {
        List<Method> methods = Arrays.stream(spy.getClass().getMethods())
                .filter(m -> m.getName().equals(methodName)).collect(Collectors.toList());
        if (methods.isEmpty()) {
            fail("出陣で " + spy.getClass().getSuperclass().getSimpleName() + "#" + methodName + " を新設すること");
        }
        for (Method method : methods) {
            Object[] matchers = new Object[method.getParameterCount()];
            Class<?>[] types = method.getParameterTypes();
            Object stubber = Mockito.doAnswer(answer).when(spy);
            for (int i = 0; i < types.length; i++) {
                matchers[i] = matcherFor(types[i]);
            }
            method.invoke(stubber, matchers);
        }
    }

    private static Object matcherFor(Class<?> type) {
        if (type == long.class) return ArgumentMatchers.anyLong();
        if (type == int.class) return ArgumentMatchers.anyInt();
        if (type == boolean.class) return ArgumentMatchers.anyBoolean();
        return ArgumentMatchers.any();
    }

    private ResultActions singleRead(Long orgId, Long feedId, Long viewer) throws Exception {
        setAuth(viewer);
        String path = "/api/v1/organizations/" + orgId + "/announcements/" + feedId + "/read";
        return mockMvc.perform(post(path).servletPath(path));
    }

    private ResultActions readBatch(Long orgId, Long viewer, List<Long> feedIds) throws Exception {
        em.flush();
        setAuth(viewer);
        String path = batchPath(orgId);
        String ids = feedIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        return mockMvc.perform(post(path).servletPath(path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"feedIds\":[" + ids + "]}"));
    }

    private static String batchPath(Long orgId) {
        return "/api/v1/organizations/" + orgId + "/announcements/read-batch";
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Long orgFeed(Long orgId, String targetTeams, String visibility) {
        BlogPostEntity post = BlogPostEntity.builder().organizationId(orgId).authorId(authorId)
                .title("組織の記事").slug("alic-rb-" + UUID.randomUUID()).body("本文")
                .visibility(AnnouncementVisibility.PUBLIC.equals(visibility) ? Visibility.PUBLIC : Visibility.MEMBERS_ONLY)
                .status(PostStatus.PUBLISHED).postType(PostType.BLOG).build();
        em.persist(post);
        AnnouncementFeedEntity feed = AnnouncementFeedEntity.builder()
                .scopeType(AnnouncementScopeType.ORGANIZATION).scopeId(orgId)
                .sourceType(AnnouncementSourceType.BLOG_POST).sourceId(post.getId()).authorId(authorId)
                .titleCache("宛先チーム向け告知").visibility(visibility).targetTeamIds(targetTeams).build();
        em.persist(feed);
        return feed.getId();
    }

    private long countRead(Long feedId, Long userId) {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM announcement_read_status "
                        + "WHERE announcement_feed_id = :f AND user_id = :u")
                .setParameter("f", feedId).setParameter("u", userId).getSingleResult()).longValue();
    }

    @SuppressWarnings("unchecked")
    private List<Long> readFeedIds(Long userId) {
        return ((List<Number>) em.createNativeQuery(
                        "SELECT announcement_feed_id FROM announcement_read_status WHERE user_id = :u")
                .setParameter("u", userId).getResultList()).stream().map(Number::longValue).toList();
    }

    private Object readAt(Long feedId, Long userId) {
        return em.createNativeQuery("SELECT read_at FROM announcement_read_status "
                        + "WHERE announcement_feed_id = :f AND user_id = :u")
                .setParameter("f", feedId).setParameter("u", userId).getSingleResult();
    }

    private Long insertUser(String name) {
        String email = "alic-rb-" + name + "@example.com";
        em.createNativeQuery("INSERT INTO users (email, last_name, first_name, display_name, status, "
                        + "is_searchable, handle_searchable, contact_approval_required, online_visibility, "
                        + "dm_receive_from, encryption_key_version, locale, timezone, reporting_restricted, "
                        + "follow_list_visibility, care_notification_enabled, offline_only, created_at, updated_at) "
                        + "VALUES (:email, '既読', '試練', :name, 'ACTIVE', 1, 1, 1, 'NOBODY', 'ANYONE', 1, "
                        + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())")
                .setParameter("email", email).setParameter("name", name).executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email).getSingleResult()).longValue();
    }
}
