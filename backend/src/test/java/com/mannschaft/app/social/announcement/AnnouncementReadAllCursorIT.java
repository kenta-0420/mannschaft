package com.mannschaft.app.social.announcement;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.payment.PaymentItemType;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
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
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ALIC-1 read-all の走査（CMP-261011-0035 試練・陣立て書 r4〜r6）。
 *
 * <ul>
 *   <li>チャンクの終端判定は課金で除外する<b>前</b>の取得件数で行う（D12・r4）。</li>
 *   <li>上限で走査が終わらないときの続きの位置はサーバ側だけで保持する。Valkey キー
 *       {@code announcement:read-all:cursor:{userId}:{scopeType}:{scopeId}}、値は直前の要求で取得した
 *       （課金除外前の）最後の feed ID の10進文字列、TTL 15分。走査を最後まで終えたらキーを消す（D12〜D16・r6）。</li>
 *   <li>応答・一覧・ヘッダーに HIDDEN の ID や走査位置を出さない（D13）。</li>
 * </ul>
 *
 * <p>基底の {@code StringRedisTemplate} は Mock なので、{@code opsForValue()} と {@code delete} を
 * インメモリ Map で裏打ちする。出陣への前提: 走査位置の保存は {@code StringRedisTemplate} の
 * {@code opsForValue().set(key, value, Duration|long+TimeUnit)} / {@code get(key)} / {@code delete(key)} で行う。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("ALIC-1 read-all の走査位置とチャンク終端（CMP-261011-0035）")
class AnnouncementReadAllCursorIT extends AbstractMySqlIntegrationTest {

    private static final int BATCH = AnnouncementReadService.MARK_ALL_BATCH_SIZE;
    private static final int MAX_SCAN = AnnouncementReadService.MARK_ALL_BATCH_SIZE
            * AnnouncementReadService.MARK_ALL_MAX_BATCHES;

    @Autowired private MockMvc mockMvc;
    @PersistenceContext private EntityManager em;

    private final Map<String, String> store = new ConcurrentHashMap<>();
    private final Map<String, Duration> ttls = new ConcurrentHashMap<>();

    private Long orgX;
    private Long t1;
    private Long memberM;
    private Long memberB;
    private Long authorId;
    private Long paymentItemId;
    private long sourceSeq = 9_100_000L;

    @BeforeEach
    void setUp() {
        store.clear();
        ttls.clear();
        stubValkeyWithMap();
        String key = UUID.randomUUID().toString().substring(0, 8);
        orgX = TeamOrgFixtureHelper.insertOrganization(em, "走査組織", "alic-cur-x-" + key);
        t1 = TeamOrgFixtureHelper.insertTeam(em, "走査T1", "alic-cur-t1-" + key);
        memberM = insertUser("m-" + key);
        memberB = insertUser("b-" + key);
        authorId = insertUser("author-" + key);
        MembershipTestHelper.insertMembership(em, memberM, ScopeType.TEAM, t1, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberB, ScopeType.TEAM, t1, RoleKind.MEMBER);
        PaymentItemEntity item = PaymentItemEntity.builder().teamId(t1).name("告知購読")
                .type(PaymentItemType.MONTHLY_FEE).amount(BigDecimal.valueOf(100)).build();
        em.persist(item);
        paymentItemId = item.getId();
        em.flush();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── D12 終端判定（r4） ─────────────────────────────────────────

    @Test
    @DisplayName("AC-ALIC1-D12 最初の500件に HIDDEN を含み501件目が可視 → 501件目も既読、HIDDEN は既読にならない")
    void D12_HIDDEN混在でも後続を取り残さない() throws Exception {
        List<Long> ids = bulkTeamFeeds(BATCH + 1, "MIX", AnnouncementVisibility.MEMBERS_AND_ABOVE);
        Long hidden = ids.get(10);
        hideFeeds(List.of(hidden));
        Long last = ids.get(BATCH);

        readAll(memberM).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.markedCount").value(BATCH))
                .andExpect(jsonPath("$.data.hasMoreUnread").value(false));
        em.flush();
        assertThat(countRead(last, memberM)).as("修正前は501件目が未読のまま（red の核心）").isEqualTo(1);
        assertThat(countRead(hidden, memberM)).as("HIDDEN は既読にしない").isZero();
        assertThat(countReadByUser(memberM)).isEqualTo(BATCH);
    }

    // ── D12/D13 上限を超えて HIDDEN が並ぶ場合（r6） ───────────────

    @Test
    @DisplayName("AC-ALIC1-D12/D13 先頭10,000件 HIDDEN・10,001件目可視: 1回目 0件・続きあり、再取得を挟んだ2回目で10,001件目が既読、位置は応答に出ない")
    void D12_上限超のHIDDENの続きをサーバ側の位置から再開する() throws Exception {
        List<Long> hiddenIds = bulkTeamFeeds(MAX_SCAN, "HID", AnnouncementVisibility.MEMBERS_AND_ABOVE);
        hideFeeds(hiddenIds);
        Long lastHidden = hiddenIds.get(hiddenIds.size() - 1);
        Long visible = bulkTeamFeeds(1, "VIS", AnnouncementVisibility.MEMBERS_AND_ABOVE).get(0);

        MvcResult first = readAll(memberM).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.markedCount").value(0))
                .andExpect(jsonPath("$.data.hasMoreUnread").value(true))
                .andReturn();
        String cursorKey = cursorKey(memberM, "TEAM", t1);
        assertThat(store).as("走査位置は利用者×スコープのキーに保存される").containsKey(cursorKey);
        assertThat(store.get(cursorKey)).as("値は課金除外前に取得した最後の feed ID")
                .isEqualTo(String.valueOf(lastHidden));
        assertThat(ttls.get(cursorKey)).as("TTL は15分").isEqualTo(Duration.ofMinutes(15));

        // AC-ALIC1-D13: 応答の JSON は従来どおり2フィールドだけ、ID・位置がどこにも出ない
        assertNoCursorLeak(first, lastHidden);

        // 一覧の再取得を挟む（FE は打ち切り直後に必ず fetchFeed する）
        String listPath = "/api/v1/teams/" + t1 + "/announcements";
        MvcResult list = mockMvc.perform(get(listPath).servletPath(listPath).param("limit", "50"))
                .andExpect(status().isOk()).andReturn();
        assertThat(list.getResponse().getContentAsString()).doesNotContain(String.valueOf(lastHidden));

        MvcResult second = readAll(memberM).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.markedCount").value(1))
                .andExpect(jsonPath("$.data.hasMoreUnread").value(false))
                .andReturn();
        assertNoCursorLeak(second, lastHidden);
        em.flush();
        assertThat(countRead(visible, memberM)).isEqualTo(1);
        assertThat(countReadByUser(memberM)).as("HIDDEN はどれも既読にならない").isEqualTo(1);
        assertThat(store).as("走査を最後まで終えたらキーを消す").doesNotContainKey(cursorKey);
    }

    @Test
    @DisplayName("AC-ALIC1-D13 read-all の応答は markedCount・hasMoreUnread の2フィールドだけ（回帰）")
    void D13_応答のフィールドは従来どおり() throws Exception {
        List<Long> ids = bulkTeamFeeds(3, "D13", AnnouncementVisibility.MEMBERS_AND_ABOVE);
        hideFeeds(List.of(ids.get(0)));
        MvcResult result = readAll(memberM).andExpect(status().isOk()).andReturn();
        assertNoCursorLeak(result, ids.get(0));
    }

    // ── D14 再開後も各チャンクで再評価 ─────────────────────────────

    @Test
    @DisplayName("AC-ALIC1-D14 保存位置から再開する前に降格した場合、位置より後ろの不可視項目に既読行はできない")
    void D14_再開時も最新の資格で評価する() throws Exception {
        Long before = bulkTeamFeeds(1, "D14P0", AnnouncementVisibility.PUBLIC).get(0);
        Long cursor = bulkTeamFeeds(1, "D14C", AnnouncementVisibility.MEMBERS_AND_ABOVE).get(0);
        Long afterMembers = bulkTeamFeeds(1, "D14M", AnnouncementVisibility.MEMBERS_AND_ABOVE).get(0);
        Long afterPublic = bulkTeamFeeds(1, "D14P", AnnouncementVisibility.PUBLIC).get(0);
        store.put(cursorKey(memberM, "TEAM", t1), String.valueOf(cursor));
        em.createNativeQuery("UPDATE memberships SET role_kind = 'SUPPORTER' WHERE user_id = :u "
                + "AND scope_type = 'TEAM' AND scope_id = :t").setParameter("u", memberM)
                .setParameter("t", t1).executeUpdate();
        em.flush();
        em.clear();

        readAll(memberM).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.markedCount").value(1));
        em.flush();
        assertThat(countRead(afterMembers, memberM)).as("降格後は MEMBERS を既読にしない").isZero();
        assertThat(countRead(afterPublic, memberM)).isEqualTo(1);
        assertThat(countRead(before, memberM)).as("保存位置以前は走査しない（位置からの再開）").isZero();
    }

    // ── D15 位置は利用者×スコープで分かれる ─────────────────────────

    @Test
    @DisplayName("AC-ALIC1-D15 自分の保存位置があればその後ろから走査し、終えたらキーを消す")
    void D15_自分の位置から再開する() throws Exception {
        List<Long> ids = bulkTeamFeeds(3, "D15A", AnnouncementVisibility.MEMBERS_AND_ABOVE);
        String key = cursorKey(memberM, "TEAM", t1);
        store.put(key, String.valueOf(ids.get(0)));

        readAll(memberM).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.markedCount").value(2))
                .andExpect(jsonPath("$.data.hasMoreUnread").value(false));
        em.flush();
        assertThat(countRead(ids.get(0), memberM)).as("位置以前は今回は走査しない").isZero();
        assertThat(countRead(ids.get(1), memberM)).isEqualTo(1);
        assertThat(countRead(ids.get(2), memberM)).isEqualTo(1);
        assertThat(store).doesNotContainKey(key);
    }

    @Test
    @DisplayName("AC-ALIC1-D15 利用者 A の位置は B に、チーム T1 の位置は組織 X の read-all に影響しない。キーが無ければ（TTL 切れ）先頭から")
    void D15_他人と他スコープの位置は使わない() throws Exception {
        Long orgFeed1 = orgFeed(AnnouncementVisibility.PUBLIC);
        Long orgFeed2 = orgFeed(AnnouncementVisibility.PUBLIC);
        List<Long> ids = bulkTeamFeeds(3, "D15B", AnnouncementVisibility.MEMBERS_AND_ABOVE);
        String keyOfM = cursorKey(memberM, "TEAM", t1);
        // M の T1 位置を最後の ID に置く。これを B や組織 X に流用すると 0 件になってしまう。
        store.put(keyOfM, String.valueOf(ids.get(2)));

        readAll(memberB).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.markedCount").value(3));
        assertThat(store).as("B の走査は M のキーに触れない").containsEntry(keyOfM, String.valueOf(ids.get(2)));

        setAuth(memberM);
        String orgPath = "/api/v1/organizations/" + orgX + "/announcements/read-all";
        mockMvc.perform(post(orgPath).servletPath(orgPath)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.markedCount").value(2));
        em.flush();
        assertThat(countRead(orgFeed1, memberM)).isEqualTo(1);
        assertThat(countRead(orgFeed2, memberM)).isEqualTo(1);
        assertThat(store).as("組織の走査も M の T1 キーに触れない").containsEntry(keyOfM, String.valueOf(ids.get(2)));

        // TTL 切れ（キー不在）なら先頭から走査する
        store.remove(keyOfM);
        readAll(memberM).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.markedCount").value(3));
        // 位置を実際に読みに行っていること（キー名の結線を確かめる）
        verify(redisTemplate, atLeastOnce()).opsForValue();
    }

    // ── D16 Valkey 障害 ──────────────────────────────────────────

    @Test
    @DisplayName("AC-ALIC1-D16 Valkey 失敗時は WARN を出し、先頭から走査して正しい結果を返す（要求は失敗しない）")
    void D16_Valkey障害でも先頭から正しく既読にする() throws Exception {
        List<Long> ids = bulkTeamFeeds(3, "D16", AnnouncementVisibility.MEMBERS_AND_ABOVE);
        RedisConnectionFailureException down = new RedisConnectionFailureException("試練: Valkey 停止");
        Mockito.reset(redisTemplate);
        Mockito.when(redisTemplate.opsForValue()).thenThrow(down);
        Mockito.when(redisTemplate.delete(anyString())).thenThrow(down);
        Mockito.when(redisTemplate.hasKey(anyString())).thenThrow(down);

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Logger appLogger = (Logger) LoggerFactory.getLogger("com.mannschaft.app");
        appender.start();
        appLogger.addAppender(appender);
        try {
            readAll(memberM).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.markedCount").value(3))
                    .andExpect(jsonPath("$.data.hasMoreUnread").value(false));
        } finally {
            appLogger.detachAppender(appender);
        }
        em.flush();
        for (Long id : ids) {
            assertThat(countRead(id, memberM)).isEqualTo(1);
        }
        verify(redisTemplate, atLeastOnce()).opsForValue();
        assertThat(appender.list).as("走査位置の保存・取得失敗は WARN で記録する（握りつぶさない）")
                .anyMatch(event -> event.getLevel() == Level.WARN);
    }

    // ── ヘルパー ─────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void stubValkeyWithMap() {
        ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);
        Mockito.when(redisTemplate.opsForValue()).thenReturn(ops);
        Mockito.when(ops.get(any())).thenAnswer(inv -> store.get(String.valueOf(inv.getArgument(0))));
        Mockito.doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            ttls.remove(String.valueOf(inv.getArgument(0)));
            return null;
        }).when(ops).set(anyString(), anyString());
        Mockito.doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            ttls.put(inv.getArgument(0), inv.getArgument(2));
            return null;
        }).when(ops).set(anyString(), anyString(), any(Duration.class));
        Mockito.doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            TimeUnit unit = inv.getArgument(3);
            ttls.put(inv.getArgument(0), Duration.ofMillis(unit.toMillis(inv.getArgument(2))));
            return null;
        }).when(ops).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        Mockito.when(ops.getAndDelete(anyString())).thenAnswer(inv -> store.remove(String.valueOf(inv.getArgument(0))));
        Mockito.when(redisTemplate.delete(anyString())).thenAnswer(inv -> {
            boolean existed = store.remove(String.valueOf(inv.getArgument(0))) != null;
            ttls.remove(String.valueOf(inv.getArgument(0)));
            return existed;
        });
        Mockito.when(redisTemplate.hasKey(anyString())).thenAnswer(inv -> store.containsKey(String.valueOf(inv.getArgument(0))));
    }

    private static String cursorKey(Long userId, String scopeType, Long scopeId) {
        return "announcement:read-all:cursor:" + userId + ":" + scopeType + ":" + scopeId;
    }

    private void assertNoCursorLeak(MvcResult result, Long hiddenId) throws Exception {
        String body = result.getResponse().getContentAsString();
        Map<String, Object> data = JsonPath.read(body, "$.data");
        assertThat(data.keySet()).as("応答フィールドは従来どおり markedCount・hasMoreUnread だけ")
                .containsExactlyInAnyOrder("markedCount", "hasMoreUnread");
        assertThat(body).doesNotContain(String.valueOf(hiddenId));
        for (String name : result.getResponse().getHeaderNames()) {
            for (String value : result.getResponse().getHeaders(name)) {
                assertThat(value).as("ヘッダー %s に HIDDEN の ID・走査位置を出さない", name)
                        .doesNotContain(String.valueOf(hiddenId));
            }
        }
    }

    private org.springframework.test.web.servlet.ResultActions readAll(Long viewer) throws Exception {
        em.flush();
        setAuth(viewer);
        String path = "/api/v1/teams/" + t1 + "/announcements/read-all";
        return mockMvc.perform(post(path).servletPath(path));
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /** チームの告知を {@code count} 件まとめて作り、ID 昇順で返す（10,000 件級でも1文で作る）。 */
    @SuppressWarnings("unchecked")
    private List<Long> bulkTeamFeeds(int count, String tag, String visibility) {
        long base = sourceSeq;
        sourceSeq += count + 10;
        String uniqueTag = tag + "-" + UUID.randomUUID().toString().substring(0, 6);
        em.createNativeQuery("INSERT INTO announcement_feeds (scope_type, scope_id, source_type, source_id, "
                        + "author_id, title_cache, priority, is_pinned, visibility, include_unassigned, "
                        + "is_advertisement, created_at, updated_at) "
                        + "SELECT 'TEAM', :tid, 'BLOG_POST', :base + n, :author, CONCAT(:tag, ' ', n), 'NORMAL', 0, "
                        + ":vis, 0, 0, NOW(), NOW() FROM (" + digits() + ") nums WHERE n < :cnt ORDER BY n")
                .setParameter("tid", t1).setParameter("base", base).setParameter("author", authorId)
                .setParameter("tag", uniqueTag).setParameter("vis", visibility).setParameter("cnt", count)
                .executeUpdate();
        List<Number> ids = em.createNativeQuery("SELECT id FROM announcement_feeds WHERE title_cache LIKE :tag "
                        + "ORDER BY id").setParameter("tag", uniqueTag + " %").getResultList();
        assertThat(ids).hasSize(count);
        return ids.stream().map(Number::longValue).toList();
    }

    /** 0〜99,999 の連番（5桁の直積）。 */
    private static String digits() {
        String d = "SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 "
                + "UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9";
        return "SELECT a.d + 10 * b.d + 100 * c.d + 1000 * e.d + 10000 * f.d AS n FROM (" + d + ") a CROSS JOIN ("
                + d + ") b CROSS JOIN (" + d + ") c CROSS JOIN (" + d + ") e CROSS JOIN (" + d + ") f";
    }

    /** 未購入かつタイトル非表示＝HIDDEN の課金ゲートを付ける。 */
    private void hideFeeds(List<Long> feedIds) {
        List<Long> sorted = new ArrayList<>(feedIds);
        Collections.sort(sorted);
        for (int from = 0; from < sorted.size(); from += 1000) {
            List<Long> part = sorted.subList(from, Math.min(sorted.size(), from + 1000));
            em.createNativeQuery("INSERT INTO content_payment_gates (payment_item_id, content_type, content_id, "
                            + "is_title_hidden, created_by, created_at) SELECT :item, 'ANNOUNCEMENT', id, 1, :author, NOW() "
                            + "FROM announcement_feeds WHERE id IN (:ids)")
                    .setParameter("item", paymentItemId).setParameter("author", authorId)
                    .setParameter("ids", part).executeUpdate();
        }
    }

    private Long orgFeed(String visibility) {
        AnnouncementFeedEntity feed = AnnouncementFeedEntity.builder()
                .scopeType(AnnouncementScopeType.ORGANIZATION).scopeId(orgX)
                .sourceType(AnnouncementSourceType.BLOG_POST).sourceId(++sourceSeq).authorId(authorId)
                .titleCache("組織告知").visibility(visibility).build();
        em.persist(feed);
        return feed.getId();
    }

    private long countRead(Long feedId, Long userId) {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM announcement_read_status "
                        + "WHERE announcement_feed_id = :f AND user_id = :u")
                .setParameter("f", feedId).setParameter("u", userId).getSingleResult()).longValue();
    }

    private long countReadByUser(Long userId) {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM announcement_read_status WHERE user_id = :u")
                .setParameter("u", userId).getSingleResult()).longValue();
    }

    private Long insertUser(String name) {
        String email = "alic-cur-" + name + "@example.com";
        em.createNativeQuery("INSERT INTO users (email, last_name, first_name, display_name, status, "
                        + "is_searchable, handle_searchable, contact_approval_required, online_visibility, "
                        + "dm_receive_from, encryption_key_version, locale, timezone, reporting_restricted, "
                        + "follow_list_visibility, care_notification_enabled, offline_only, created_at, updated_at) "
                        + "VALUES (:email, '走査', '試練', :name, 'ACTIVE', 1, 1, 1, 'NOBODY', 'ANYONE', 1, "
                        + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())")
                .setParameter("email", email).setParameter("name", name).executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email).getSingleResult()).longValue();
    }
}
