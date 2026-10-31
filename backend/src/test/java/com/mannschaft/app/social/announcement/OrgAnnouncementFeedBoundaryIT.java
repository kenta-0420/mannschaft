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
import org.mockito.Mockito;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ValueOperations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-261011-0035 組織告知一覧の境界（Codex 検分1回目の差し戻し分）。
 * 元ブログの課金 HIDDEN（管理者・システム管理者含む）、CUSTOM 課金ブログ（LOCKED で 200 になるものを一覧から落とさず、
 * SQL 本数が件数に比例しない）、ピン留めと宛先除外を組み合わせたページ送り、走査上限に達した後の再開。
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
@DisplayName("CMP-261011-0035 組織告知一覧の境界（課金 HIDDEN・CUSTOM・ピン留め・走査上限）")
class OrgAnnouncementFeedBoundaryIT extends AbstractMySqlIntegrationTest {

    // OrgAnnouncementFeedAudienceIT と同じ上書き構成にして TestContext を共有する
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

    /** OrgAnnouncementFeedQueryService.MAX_SCANNED_ROWS（1 リクエストの走査上限）と同じ値。 */
    private static final int SCAN_LIMIT = 2000;

    private Long orgX;
    private UUID east;
    private Long t1;
    private Long author;
    private PaymentItemEntity item;
    private Map<String, Long> people;
    /** Valkey（継続トークン）をインメモリで裏打ちする。 */
    private final Map<String, String> store = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    private void stubValkeyWithMap() {
        store.clear();
        ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);
        Mockito.when(redisTemplate.opsForValue()).thenReturn(ops);
        Mockito.when(ops.get(any())).thenAnswer(inv -> store.get(String.valueOf((Object) inv.getArgument(0))));
        Mockito.when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(inv ->
                store.putIfAbsent(inv.getArgument(0), inv.getArgument(1)) == null);
    }

    @BeforeEach
    void setUp() {
        stubValkeyWithMap();
        String key = UUID.randomUUID().toString().substring(0, 8);
        orgX = TeamOrgFixtureHelper.insertOrganization(em, "境界の組織X", "bnd-x-" + key);
        OrgTeamGroupEntity group = OrgTeamGroupEntity.builder().organizationId(orgX).name("東" + key).build();
        em.persist(group);
        east = group.getId();
        t1 = TeamOrgFixtureHelper.insertTeam(em, "T1", "bnd-t1-" + key);
        em.persist(TeamOrgMembershipEntity.builder().teamId(t1).organizationId(orgX)
                .status(TeamOrgMembershipEntity.Status.ACTIVE).groupId(east)
                .invitedAt(LocalDateTime.now(wallClock).minusDays(2))
                .respondedAt(LocalDateTime.now(wallClock).minusDays(1)).build());

        author = saveUser("author-" + key);
        MembershipTestHelper.insertMembership(em, author, ScopeType.ORGANIZATION, orgX, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, author, "ADMIN", null, orgX);
        item = PaymentItemEntity.builder().organizationId(orgX).name("境界の購読")
                .type(PaymentItemType.MONTHLY_FEE).amount(BigDecimal.valueOf(100)).build();
        em.persist(item);

        people = new LinkedHashMap<>();
        people.put("N", saveUser("n-" + key));
        Long om = saveUser("om-" + key);
        MembershipTestHelper.insertMembership(em, om, ScopeType.ORGANIZATION, orgX, RoleKind.MEMBER);
        people.put("OM", om);
        Long oa = saveUser("oa-" + key);
        MembershipTestHelper.insertMembership(em, oa, ScopeType.ORGANIZATION, orgX, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, oa, "ADMIN", null, orgX);
        people.put("OA", oa);
        Long sa = saveUser("sa-" + key);
        MembershipTestHelper.insertUserRole(em, sa, "SYSTEM_ADMIN", null, null);
        people.put("SA", sa);
        em.flush();
    }

    // ═════════════════ 1. 元ブログの課金 HIDDEN ═════════════════

    @Test
    @DisplayName("元ブログが課金 HIDDEN: 組織 ADMIN・SYSTEM_ADMIN の一覧にも出ず、プレビューも 404（一致）")
    void 元ブログのHIDDENは管理者の一覧にも出ない() throws Exception {
        BlogPostEntity blog = blog(Visibility.PUBLIC);
        gate(blog.getId(), true);
        AnnouncementFeedEntity feed = feed(blog.getId(), null);
        for (String who : List.of("N", "OM", "OA", "SA")) {
            assertThat(previewStatus(feed, who)).as(who + " のプレビュー").isEqualTo(404);
            assertThat(listedIds(who, 50, null)).as(who + " の一覧").doesNotContain(feed.getId());
        }
    }

    // ═════════════════ 2. CUSTOM 課金ブログ ═════════════════

    @Test
    @DisplayName("元ブログが CUSTOM 課金（未払いでもタイトル可視）: プレビューが 200（LOCKED）の人の一覧から落とさない")
    void CUSTOM課金ブログは一覧とプレビューが一致する() throws Exception {
        BlogPostEntity blog = blog(Visibility.CUSTOM);
        gate(blog.getId(), false);
        AnnouncementFeedEntity feed = feed(blog.getId(), null);
        for (String who : people.keySet()) {
            boolean readable = previewStatus(feed, who) != 404;
            assertThat(listedIds(who, 50, null).contains(feed.getId())).as(who + " の一覧⇔プレビュー").isEqualTo(readable);
        }
        assertThat(previewStatus(feed, "OM")).as("前提: 直属 MEMBER は LOCKED で 200").isEqualTo(200);
        assertThat(listedIds("OM", 50, null)).contains(feed.getId());
    }

    @Test
    @DisplayName("CUSTOM 課金ブログの一覧の SQL 本数は 1 件と 20 件で差がない")
    void CUSTOM課金ブログの本数は件数に比例しない() throws Exception {
        BlogPostEntity first = blog(Visibility.CUSTOM);
        gate(first.getId(), false);
        feed(first.getId(), null);
        listedIds("OM", 50, null); // ウォームアップ
        int one = countList("OM");
        for (int i = 0; i < 19; i++) {
            BlogPostEntity b = blog(Visibility.CUSTOM);
            gate(b.getId(), false);
            feed(b.getId(), null);
        }
        int twenty = countList("OM");
        assertThat(twenty).as("1件: %d 本 / 20件: %d 本", one, twenty).isEqualTo(one);
    }

    // ═════════════════ 3. ピン留めとページ送り ═════════════════

    @Test
    @DisplayName("宛先外のピン留め告知が先頭に並んでも、limit=1 で後ろの読める告知を飛ばさない（補充取得）")
    void ピン留めの宛先外が先頭でも読める告知を飛ばさない() throws Exception {
        List<AnnouncementFeedEntity> hiddenPinned = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            AnnouncementFeedEntity pinned = feed(blog(Visibility.PUBLIC).getId(), groups(east));
            pinned.markPinned(author);
            hiddenPinned.add(pinned);
        }
        AnnouncementFeedEntity readable = feed(blog(Visibility.PUBLIC).getId(), null);
        List<Long> ids = listedIds("N", 1, null);
        assertThat(ids).as("N の limit=1 の一覧").containsExactly(readable.getId());
    }

    @Test
    @DisplayName("ピン留め→新着の並びでカーソルを辿っても、ピン留めの後ろの通常告知を飛ばさず重複もしない")
    void カーソルで辿ると全件が並び順どおり1回ずつ出る() throws Exception {
        AnnouncementFeedEntity pinned = feed(blog(Visibility.PUBLIC).getId(), null);
        pinned.markPinned(author);
        AnnouncementFeedEntity n1 = feed(blog(Visibility.PUBLIC).getId(), null);
        AnnouncementFeedEntity n2 = feed(blog(Visibility.PUBLIC).getId(), null);
        AnnouncementFeedEntity n3 = feed(blog(Visibility.PUBLIC).getId(), null);
        List<Long> collected = new ArrayList<>();
        Long cursor = null;
        for (int guard = 0; guard < 10; guard++) {
            JsonNode body = list("OM", 1, cursor);
            body.path("data").forEach(item -> collected.add(item.path("id").asLong()));
            if (!body.path("meta").path("hasNext").asBoolean()) {
                break;
            }
            cursor = body.path("meta").path("nextCursor").asLong();
        }
        // ピン留め先頭、以降は新しい順（同時刻は ID の大きい順）
        assertThat(collected).containsExactly(pinned.getId(), n3.getId(), n2.getId(), n1.getId());
    }

    // ═════════════════ 4. 走査上限 ═════════════════

    @Test
    @DisplayName("走査上限まで読んだ行が全部宛先外でも、空ページに続きの位置を返し、次の要求でその先の読める告知に届く")
    void 走査上限に達しても続きから再開できる() throws Exception {
        AnnouncementFeedEntity readable = feed(blog(Visibility.PUBLIC).getId(), null);
        // 読める告知より新しい宛先外の告知を、1 リクエストの走査上限（2000 行）より多く積む
        BlogPostEntity sharedBlog = blog(Visibility.PUBLIC);
        for (int i = 0; i < SCAN_LIMIT + 1; i++) {
            feed(sharedBlog.getId(), groups(east));
            if (i % 500 == 0) {
                em.flush();
            }
        }
        JsonNode first = list("N", 1, null);
        assertThat(first.path("data")).as("1 回目は走査上限で空").isEmpty();
        assertThat(first.path("meta").path("hasNext").asBoolean()).isTrue();
        assertThat(first.path("meta").path("nextCursor").isNull()).as("続きの位置を返す").isFalse();
        JsonNode second = list("N", 1, first.path("meta").path("nextCursor").asLong());
        List<Long> ids = new ArrayList<>();
        second.path("data").forEach(item -> ids.add(item.path("id").asLong()));
        assertThat(ids).as("2 回目で読める告知に届く").containsExactly(readable.getId());
    }

    @Test
    @DisplayName("空ページで返す継続トークンは、どの告知 ID とも一致しない（読めない告知の実在 ID を出さない）")
    void 空ページのトークンは告知IDではない() throws Exception {
        feed(blog(Visibility.PUBLIC).getId(), null);
        BlogPostEntity sharedBlog = blog(Visibility.PUBLIC);
        for (int i = 0; i < SCAN_LIMIT + 1; i++) {
            feed(sharedBlog.getId(), groups(east));
            if (i % 500 == 0) {
                em.flush();
            }
        }
        em.flush();
        Set<Long> allFeedIds = new HashSet<>(em.createQuery(
                "SELECT a.id FROM AnnouncementFeedEntity a WHERE a.scopeId = :o", Long.class)
                .setParameter("o", orgX).getResultList());
        JsonNode first = list("N", 1, null);
        assertThat(first.path("data")).isEmpty();
        long token = first.path("meta").path("nextCursor").asLong();
        assertThat(token).as("継続トークン").isBetween(1L, (1L << 53) - 1);
        assertThat(allFeedIds).as("告知 ID のどれとも一致しない").doesNotContain(token);
        assertThat(store.keySet()).as("利用者×組織×トークンに結び付く")
                .contains("announcement:org-feed:cursor:" + people.get("N") + ":" + orgX + ":" + token);
    }

    // ═════════════════ 5. 継続トークン ═════════════════

    @Test
    @DisplayName("告知 ID・存在しない値・他組織の ID・不正値を cursor に渡すと、すべて同一の 400（COMMON_001）")
    void 告知IDをカーソルに渡すと同一の400() throws Exception {
        AnnouncementFeedEntity readable = feed(blog(Visibility.PUBLIC).getId(), null);
        AnnouncementFeedEntity hiddenPinned = feed(blog(Visibility.PUBLIC).getId(), groups(east));
        hiddenPinned.markPinned(author);
        em.flush();
        String otherKey = UUID.randomUUID().toString().substring(0, 8);
        Long otherOrg = TeamOrgFixtureHelper.insertOrganization(em, "別組織", "bnd-o-" + otherKey);
        AnnouncementFeedEntity otherOrgFeed = AnnouncementFeedEntity.builder()
                .scopeType(AnnouncementScopeType.ORGANIZATION).scopeId(otherOrg)
                .sourceType(AnnouncementSourceType.BLOG_POST).sourceId(blog(Visibility.PUBLIC).getId()).authorId(author)
                .titleCache("他組織").visibility(AnnouncementVisibility.PUBLIC).build();
        em.persist(otherOrgFeed);
        em.flush();
        Set<String> bodies = new HashSet<>();
        for (long cursor : new long[] {readable.getId(), hiddenPinned.getId(), otherOrgFeed.getId(), 99_999_999L, 0L, -5L,
                Long.MAX_VALUE}) {
            em.flush();
            String path = "/api/v1/organizations/" + orgX + "/announcements";
            var result = mockMvc.perform(get(path).servletPath(path).param("cursor", String.valueOf(cursor))
                            .with(user(people.get("N").toString())))
                    .andExpect(status().isBadRequest()).andReturn();
            JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
            assertThat(body.path("error").path("code").asText()).as("cursor=" + cursor).isEqualTo("COMMON_001");
            bodies.add(body.path("error").path("code").asText() + "|" + body.path("error").path("message").asText());
        }
        assertThat(bodies).as("エラーの内容まで同一").hasSize(1);
    }

    @Test
    @DisplayName("期限切れ（キーが消えた）・他人のトークンは同一の 400")
    void 期限切れと他人のトークンは400() throws Exception {
        for (int i = 0; i < 3; i++) {
            feed(blog(Visibility.PUBLIC).getId(), null);
        }
        long token = list("OM", 1, null).path("meta").path("nextCursor").asLong();
        assertThat(token).isPositive();
        // 他人（同じ組織の別の利用者）のトークン
        expect400("OA", token);
        // 期限切れ（Valkey のキーが消えた）
        store.clear();
        expect400("OM", token);
    }

    @Test
    @DisplayName("カーソル行が削除されても、保存した位置から飛ばし・重複なく続けられる")
    void カーソル行の削除後も続けられる() throws Exception {
        AnnouncementFeedEntity pinned = feed(blog(Visibility.PUBLIC).getId(), null);
        pinned.markPinned(author);
        AnnouncementFeedEntity n1 = feed(blog(Visibility.PUBLIC).getId(), null);
        AnnouncementFeedEntity n2 = feed(blog(Visibility.PUBLIC).getId(), null);
        AnnouncementFeedEntity n3 = feed(blog(Visibility.PUBLIC).getId(), null);
        JsonNode first = list("OM", 1, null);
        assertThat(first.path("data").get(0).path("id").asLong()).isEqualTo(pinned.getId());
        long token = first.path("meta").path("nextCursor").asLong();
        em.remove(pinned);
        em.flush();
        List<Long> rest = new ArrayList<>();
        collectFrom("OM", token, rest);
        assertThat(rest).containsExactly(n3.getId(), n2.getId(), n1.getId());
    }

    @Test
    @DisplayName("カーソル行のピン留めが解除されても、後続を飛ばさない（解除された行は通常の位置に 1 回だけ現れうる）")
    void カーソル行のピン留め解除後も後続を飛ばさない() throws Exception {
        AnnouncementFeedEntity pinned = feed(blog(Visibility.PUBLIC).getId(), null);
        pinned.markPinned(author);
        AnnouncementFeedEntity n1 = feed(blog(Visibility.PUBLIC).getId(), null);
        AnnouncementFeedEntity n2 = feed(blog(Visibility.PUBLIC).getId(), null);
        AnnouncementFeedEntity n3 = feed(blog(Visibility.PUBLIC).getId(), null);
        long token = list("OM", 1, null).path("meta").path("nextCursor").asLong();
        pinned.unpin();
        em.flush();
        List<Long> rest = new ArrayList<>();
        collectFrom("OM", token, rest);
        Long pinnedId = pinned.getId();
        assertThat(rest.stream().filter(id -> !id.equals(pinnedId)).toList())
                .containsExactly(n3.getId(), n2.getId(), n1.getId());
        assertThat(rest.stream().filter(id -> id.equals(pinnedId)).count()).isLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Valkey に保存できないとき、続きを隠さず 503（ANNOUNCE_011）で失敗する。読み出せないときも同様")
    void Valkey障害は503で失敗する() throws Exception {
        for (int i = 0; i < 3; i++) {
            feed(blog(Visibility.PUBLIC).getId(), null);
        }
        long token = list("OM", 1, null).path("meta").path("nextCursor").asLong();
        Mockito.when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));
        String path = "/api/v1/organizations/" + orgX + "/announcements";
        // 発行できない（続きがあるページ）
        em.flush();
        var issue = mockMvc.perform(get(path).servletPath(path).param("limit", "1")
                        .with(user(people.get("OM").toString())))
                .andExpect(status().isServiceUnavailable()).andReturn();
        assertThat(objectMapper.readTree(issue.getResponse().getContentAsString()).path("error").path("code").asText())
                .isEqualTo("ANNOUNCE_011");
        // 読み出せない
        var read = mockMvc.perform(get(path).servletPath(path).param("cursor", String.valueOf(token))
                        .with(user(people.get("OM").toString())))
                .andExpect(status().isServiceUnavailable()).andReturn();
        assertThat(objectMapper.readTree(read.getResponse().getContentAsString()).path("error").path("code").asText())
                .isEqualTo("ANNOUNCE_011");
        // 続きが無いページは Valkey を使わないので成功する
        assertThat(listedIds("OM", 50, null)).hasSize(3);
    }

    // ═════════════════ 6. 開始日時 ═════════════════

    @Test
    @DisplayName("開始日時が未来の告知は、プレビューが 404 なので一覧にも出さない。過去・なしは一覧に出てプレビュー 200")
    void 開始日時が未来の告知は一覧に出ない() throws Exception {
        AnnouncementFeedEntity future = feed(blog(Visibility.PUBLIC).getId(), null);
        setStartsAt(future, LocalDateTime.of(2099, 1, 1, 0, 0));
        AnnouncementFeedEntity past = feed(blog(Visibility.PUBLIC).getId(), null);
        setStartsAt(past, LocalDateTime.of(2020, 1, 1, 0, 0));
        AnnouncementFeedEntity none = feed(blog(Visibility.PUBLIC).getId(), null);
        for (String who : List.of("N", "OM", "OA", "SA")) {
            assertThat(previewStatus(future, who)).as(who + " 未来のプレビュー").isEqualTo(404);
            assertThat(listedIds(who, 50, null)).as(who + " の一覧")
                    .doesNotContain(future.getId()).contains(past.getId(), none.getId());
            assertThat(previewStatus(past, who)).isEqualTo(200);
        }
    }

    // ═════════════════ 7. 準備中・削除済みの組織 ═════════════════

    @Test
    @DisplayName("論理削除された組織の告知は、SYSTEM_ADMIN を含め一覧に出ず、プレビューも 404（一致）")
    void 削除済み組織の告知は一覧に出ない() throws Exception {
        AnnouncementFeedEntity feed = feed(blog(Visibility.PUBLIC).getId(), null);
        for (String who : List.of("N", "OM", "OA", "SA")) {
            assertThat(previewStatus(feed, who)).as(who + " 削除前のプレビュー").isEqualTo(200);
        }
        em.flush();
        em.createNativeQuery("UPDATE organizations SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", orgX).executeUpdate();
        em.clear();
        for (String who : List.of("N", "OM", "OA", "SA")) {
            assertThat(previewStatus(feed, who)).as(who + " のプレビュー").isEqualTo(404);
            assertThat(listedIdsOrEmpty(who)).as(who + " の一覧").doesNotContain(feed.getId());
        }
    }

    @Test
    @DisplayName("準備中（PROVISIONED）の組織の告知は、SYSTEM_ADMIN を含め一覧に出ず、プレビューも 404（一致）")
    void 準備中組織の告知は一覧に出ない() throws Exception {
        AnnouncementFeedEntity feed = feed(blog(Visibility.PUBLIC).getId(), null);
        em.flush();
        em.createNativeQuery("UPDATE organizations SET lifecycle_status = 'PROVISIONED' WHERE id = :id")
                .setParameter("id", orgX).executeUpdate();
        em.clear();
        for (String who : List.of("N", "OM", "OA", "SA")) {
            assertThat(previewStatus(feed, who)).as(who + " のプレビュー").isEqualTo(404);
            assertThat(listedIdsOrEmpty(who)).as(who + " の一覧").doesNotContain(feed.getId());
        }
    }

    /** 組織の状態によっては一覧そのものが 200 以外になりうる。その場合は「出ない」として扱う。 */
    private List<Long> listedIdsOrEmpty(String who) throws Exception {
        em.flush();
        String path = "/api/v1/organizations/" + orgX + "/announcements";
        var result = mockMvc.perform(get(path).servletPath(path).param("limit", "50")
                .with(user(people.get(who).toString()))).andReturn();
        List<Long> ids = new ArrayList<>();
        if (result.getResponse().getStatus() == 200) {
            objectMapper.readTree(result.getResponse().getContentAsString()).path("data")
                    .forEach(item -> ids.add(item.path("id").asLong()));
        }
        return ids;
    }

    private void setStartsAt(AnnouncementFeedEntity feed, LocalDateTime startsAt) {
        em.flush();
        em.createNativeQuery("UPDATE announcement_feeds SET starts_at = :s WHERE id = :id")
                .setParameter("s", startsAt).setParameter("id", feed.getId()).executeUpdate();
        em.clear();
    }

    private void expect400(String who, long token) throws Exception {
        em.flush();
        String path = "/api/v1/organizations/" + orgX + "/announcements";
        var result = mockMvc.perform(get(path).servletPath(path).param("cursor", String.valueOf(token))
                        .with(user(people.get(who).toString())))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).path("error").path("code").asText())
                .isEqualTo("COMMON_001");
    }

    /** 継続トークンを辿って（limit=1）残りを集める。 */
    private void collectFrom(String who, long token, List<Long> out) throws Exception {
        Long cursor = token;
        for (int guard = 0; guard < 20 && cursor != null; guard++) {
            JsonNode body = list(who, 1, cursor);
            body.path("data").forEach(item -> out.add(item.path("id").asLong()));
            cursor = body.path("meta").path("hasNext").asBoolean() ? body.path("meta").path("nextCursor").asLong() : null;
        }
    }


    // ═════════════════ 観測 ═════════════════

    private int countList(String who) throws Exception {
        em.flush();
        em.clear();
        SqlIntentCounter.reset();
        JsonNode body = list(who, 50, null);
        int count = SqlIntentCounter.totalCount();
        assertThat(body.path("data").size()).isPositive();
        return count;
    }

    private JsonNode list(String who, int limit, Long cursor) throws Exception {
        em.flush();
        String path = "/api/v1/organizations/" + orgX + "/announcements";
        var request = get(path).servletPath(path).param("limit", String.valueOf(limit))
                .with(user(people.get(who).toString()));
        if (cursor != null) {
            request = request.param("cursor", cursor.toString());
        }
        String json = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private List<Long> listedIds(String who, int limit, Long cursor) throws Exception {
        List<Long> ids = new ArrayList<>();
        list(who, limit, cursor).path("data").forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
    }

    private int previewStatus(AnnouncementFeedEntity feed, String who) throws Exception {
        em.flush();
        String path = "/api/v1/organizations/" + orgX + "/announcements/" + feed.getId() + "/preview";
        return mockMvc.perform(get(path).servletPath(path).with(user(people.get(who).toString())))
                .andReturn().getResponse().getStatus();
    }

    // ═════════════════ フィクスチャ ═════════════════

    private BlogPostEntity blog(Visibility visibility) {
        BlogPostEntity post = BlogPostEntity.builder().organizationId(orgX).authorId(author)
                .title("境界の記事").slug("bnd-" + UUID.randomUUID()).body("境界の本文").visibility(visibility)
                .status(PostStatus.PUBLISHED).postType(PostType.BLOG).build();
        em.persist(post);
        return post;
    }

    private void gate(Long blogId, boolean titleHidden) {
        em.persist(ContentPaymentGateEntity.builder().paymentItemId(item.getId()).contentType("POST")
                .contentId(blogId).isTitleHidden(titleHidden).createdBy(author).build());
    }

    /** 組織 X の PUBLIC 告知（元はブログ）。groups が null なら全チーム宛て。 */
    private AnnouncementFeedEntity feed(Long blogId, String groups) {
        AnnouncementFeedEntity feed = AnnouncementFeedEntity.builder().scopeType(AnnouncementScopeType.ORGANIZATION)
                .scopeId(orgX).sourceType(AnnouncementSourceType.BLOG_POST).sourceId(blogId).authorId(author)
                .titleCache("境界の告知").excerptCache("告知の要約").visibility(AnnouncementVisibility.PUBLIC)
                .targetGroupIds(groups).build();
        em.persist(feed);
        return feed;
    }

    private static String groups(UUID id) {
        return "[\"" + id + "\"]";
    }

    private Long saveUser(String name) {
        UserEntity entity = UserEntity.builder().email(name + "@feed-boundary.example.com")
                .lastName("試練").firstName("利用者").displayName(name).isSearchable(true)
                .locale("ja").timezone("Asia/Tokyo").status(UserEntity.UserStatus.ACTIVE).build();
        em.persist(entity);
        return entity.getId();
    }
}
