package com.mannschaft.app.social.announcement;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
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
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ALIC-1 既読のトランザクション境界（CMP-261011-0035 試練・陣立て書 r3 §2.4/§2.5・r4 §2）。
 *
 * <p>read-all は「判定は tx 外・書き込みは500件チャンクごとに1 tx・各チャンクの直前に資格を再判定」、
 * read-batch は「判定を全部終えてから1 tx で書く」。commit/rollback を実 DB で観測するため、
 * <b>クラスに {@code @Transactional} を付けない</b>（フィクスチャは {@link TransactionTemplate} で commit し、
 * {@link #tearDown} で自分の作った行だけを消す）。</p>
 *
 * <p>注入点は既存の {@link AnnouncementReadStatusRepository#insertReadStatusesIgnoringExisting}
 * （出陣後もチャンクの書き込みはこの UPSERT 1本、陣立て書 r3 §2.7）。
 * 出陣前の挙動: read-all はクラス全体 1 tx でループ前に1度だけ役割を解決するため、
 * 途中の離脱・降格を無視して全チャンクを書き（D10 red）、2チャンク目の失敗で1チャンク目まで巻き戻る（D11 red）。
 * read-batch は EP が無い（D04 red）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("ALIC-1 既読のチャンクごとの tx と再判定（CMP-261011-0035）")
class AnnouncementReadChunkTransactionIT extends AbstractMySqlIntegrationTest {

    private static final int BATCH = AnnouncementReadService.MARK_ALL_BATCH_SIZE;
    /** 3チャンクにまたがる未読件数。 */
    private static final int UNREAD = BATCH * 2 + 250;

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager em;

    @MockitoSpyBean private AnnouncementReadStatusRepository readStatusRepository;

    private TransactionTemplate tx;
    private TransactionTemplate requiresNew;
    private String key;
    private Long orgX;
    private Long t1;
    private Long memberM;
    private Long authorId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        key = UUID.randomUUID().toString().substring(0, 8);
        tx.executeWithoutResult(status -> {
            orgX = TeamOrgFixtureHelper.insertOrganization(em, "tx組織", "alic-tx-x-" + key);
            t1 = TeamOrgFixtureHelper.insertTeam(em, "txT1", "alic-tx-t1-" + key);
            TeamOrgFixtureHelper.insertTeamOrgMembership(em, t1, orgX, "ACTIVE", LocalDateTime.now().minusDays(3));
            memberM = insertUser("m");
            authorId = insertUser("author");
            MembershipTestHelper.insertMembership(em, memberM, ScopeType.TEAM, t1, RoleKind.MEMBER);
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM announcement_read_status WHERE user_id IN (?, ?)", memberM, authorId);
        jdbc.update("DELETE FROM announcement_feeds WHERE title_cache LIKE ?", "alic-tx-" + key + "%");
        jdbc.update("DELETE FROM team_org_memberships WHERE team_id = ?", t1);
        jdbc.update("DELETE FROM memberships WHERE user_id IN (?, ?)", memberM, authorId);
        jdbc.update("DELETE FROM teams WHERE id = ?", t1);
        jdbc.update("DELETE FROM organizations WHERE id = ?", orgX);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", memberM, authorId);
    }

    // ── D10 チャンクごとの再判定 ───────────────────────────────────

    @Test
    @DisplayName("AC-ALIC1-D10 read-all の1チャンク目の書き込み直後にチームを離脱すると、2チャンク目以降の MEMBERS に既読行ができない")
    void D10_1チャンク目の直後に離脱したら後続を既読にしない() throws Exception {
        List<Long> ids = teamFeeds(UNREAD);
        afterFirstWrite("UPDATE memberships SET left_at = NOW() WHERE user_id = ? AND scope_type = 'TEAM' AND scope_id = ?");

        readAllTeam().andExpect(status().isOk());

        assertThat(countReadByUser(memberM)).as("1チャンク目（判定済みの1単位）だけが既読").isEqualTo(BATCH);
        assertThat(countRead(ids.get(BATCH - 1))).isEqualTo(1);
        assertThat(countRead(ids.get(BATCH))).as("2チャンク目の先頭は離脱後の資格で再判定され不可視").isZero();
        assertThat(countRead(ids.get(UNREAD - 1))).isZero();
    }

    @Test
    @DisplayName("AC-ALIC1-D10 1チャンク目の直後に MEMBER→SUPPORTER 降格しても、2チャンク目以降の MEMBERS に既読行ができない")
    void D10_1チャンク目の直後に降格したら後続を既読にしない() throws Exception {
        List<Long> ids = teamFeeds(UNREAD);
        afterFirstWrite("UPDATE memberships SET role_kind = 'SUPPORTER' WHERE user_id = ? AND scope_type = 'TEAM' AND scope_id = ?");

        readAllTeam().andExpect(status().isOk());

        assertThat(countReadByUser(memberM)).isEqualTo(BATCH);
        assertThat(countRead(ids.get(BATCH))).isZero();
    }

    // ── D11 チャンクごとの原子性 ───────────────────────────────────

    @Test
    @DisplayName("AC-ALIC1-D11 2チャンク目の書き込み失敗では1チャンク目の行だけ残り2チャンク目は0行、再実行で残りが既読になり件数と hasMoreUnread が整合")
    void D11_2チャンク目の失敗は1チャンク目を巻き戻さない() throws Exception {
        List<Long> ids = teamFeeds(UNREAD);
        AtomicInteger calls = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) {
                invocation.callRealMethod();
                throw new DataAccessResourceFailureException("試練: 2チャンク目の書き込み失敗");
            }
            return invocation.callRealMethod();
        }).when(readStatusRepository).insertReadStatusesIgnoringExisting(
                ArgumentMatchers.anyLong(), ArgumentMatchers.anyCollection());

        readAllTeam().andExpect(status().isInternalServerError());
        assertThat(countReadByUser(memberM)).as("1チャンク目は commit 済みで残る").isEqualTo(BATCH);
        assertThat(countRead(ids.get(BATCH))).as("2チャンク目は巻き戻って0行").isZero();

        readAllTeam().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.markedCount").value(UNREAD - BATCH))
                .andExpect(jsonPath("$.data.hasMoreUnread").value(false));
        assertThat(countReadByUser(memberM)).isEqualTo(UNREAD);
    }

    // ── D04 read-batch の書き込み失敗 ─────────────────────────────

    @Test
    @DisplayName("AC-ALIC1-D04 read-batch の書き込み中失敗は1 tx 巻き戻しで既読行0・500")
    void D04_readBatchの書き込み失敗は全件巻き戻す() throws Exception {
        Long a = orgFeed("[" + t1 + "]");
        Long b = orgFeed("[" + t1 + "]");
        Mockito.doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new DataAccessResourceFailureException("試練: read-batch 書き込み失敗");
        }).when(readStatusRepository).insertReadStatusesIgnoringExisting(
                ArgumentMatchers.anyLong(), ArgumentMatchers.anyCollection());

        setAuth(memberM);
        String path = "/api/v1/organizations/" + orgX + "/announcements/read-batch";
        mockMvc.perform(post(path).servletPath(path).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedIds\":[" + a + "," + b + "]}"))
                .andExpect(status().isInternalServerError());
        Mockito.verify(readStatusRepository, Mockito.atLeastOnce())
                .insertReadStatusesIgnoringExisting(ArgumentMatchers.eq(memberM), ArgumentMatchers.anyCollection());
        assertThat(countReadByUser(memberM)).isZero();
    }

    // ── ヘルパー ─────────────────────────────────────────────────

    /** 1回目の UPSERT の直後に、別 tx（commit 済み）で所属を変える。 */
    private void afterFirstWrite(String membershipUpdateSql) {
        AtomicInteger calls = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (calls.incrementAndGet() == 1) {
                requiresNew.executeWithoutResult(s -> jdbc.update(membershipUpdateSql, memberM, t1));
            }
            return result;
        }).when(readStatusRepository).insertReadStatusesIgnoringExisting(
                ArgumentMatchers.anyLong(), ArgumentMatchers.anyCollection());
    }

    private ResultActions readAllTeam() throws Exception {
        setAuth(memberM);
        String path = "/api/v1/teams/" + t1 + "/announcements/read-all";
        return mockMvc.perform(post(path).servletPath(path));
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private List<Long> teamFeeds(int count) {
        String d = "SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 "
                + "UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9";
        String nums = "SELECT a.d + 10 * b.d + 100 * c.d + 1000 * e.d AS n FROM (" + d + ") a CROSS JOIN (" + d
                + ") b CROSS JOIN (" + d + ") c CROSS JOIN (" + d + ") e";
        jdbc.update("INSERT INTO announcement_feeds (scope_type, scope_id, source_type, source_id, author_id, "
                        + "title_cache, priority, is_pinned, visibility, include_unassigned, is_advertisement, "
                        + "created_at, updated_at) SELECT 'TEAM', ?, 'BLOG_POST', ? + n, ?, CONCAT(?, n), 'NORMAL', 0, "
                        + "'MEMBERS_AND_ABOVE', 0, 0, NOW(), NOW() FROM (" + nums + ") x WHERE n < ? ORDER BY n",
                t1, 8_800_000L + Math.abs(key.hashCode() % 100_000) * 10L, authorId, "alic-tx-" + key + "-t-", count);
        List<Long> ids = jdbc.queryForList("SELECT id FROM announcement_feeds WHERE title_cache LIKE ? ORDER BY id",
                Long.class, "alic-tx-" + key + "-t-%");
        assertThat(ids).hasSize(count);
        return ids;
    }

    private Long orgFeed(String targetTeams) {
        String title = "alic-tx-" + key + "-o-" + UUID.randomUUID().toString().substring(0, 6);
        jdbc.update("INSERT INTO announcement_feeds (scope_type, scope_id, source_type, source_id, author_id, "
                        + "title_cache, priority, is_pinned, visibility, target_team_ids, include_unassigned, "
                        + "is_advertisement, created_at, updated_at) VALUES ('ORGANIZATION', ?, 'BULLETIN_THREAD', ?, ?, ?, "
                        + "'NORMAL', 0, 'MEMBERS_AND_ABOVE', CAST(? AS JSON), 0, 0, NOW(), NOW())",
                orgX, 7_700_000L + (long) (Math.random() * 1_000_000), authorId, title, targetTeams);
        return jdbc.queryForObject("SELECT id FROM announcement_feeds WHERE title_cache = ?", Long.class, title);
    }

    private long countRead(Long feedId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM announcement_read_status WHERE announcement_feed_id = ? "
                + "AND user_id = ?", Long.class, feedId, memberM);
    }

    private long countReadByUser(Long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM announcement_read_status WHERE user_id = ?",
                Long.class, userId);
    }

    private Long insertUser(String role) {
        String email = "alic-tx-" + role + "-" + key + "@example.com";
        em.createNativeQuery("INSERT INTO users (email, last_name, first_name, display_name, status, "
                        + "is_searchable, handle_searchable, contact_approval_required, online_visibility, "
                        + "dm_receive_from, encryption_key_version, locale, timezone, reporting_restricted, "
                        + "follow_list_visibility, care_notification_enabled, offline_only, created_at, updated_at) "
                        + "VALUES (:email, 'tx', '試練', :name, 'ACTIVE', 1, 1, 1, 'NOBODY', 'ANYONE', 1, "
                        + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())")
                .setParameter("email", email).setParameter("name", "tx-" + role).executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email).getSingleResult()).longValue();
    }
}
