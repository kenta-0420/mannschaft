package com.mannschaft.app.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-261113-0110 名前なし GROUP_DM（Zimmer）の導出表示名 {@code derivedName} の受け入れ契約テスト（試練・red 先行）。
 *
 * <p><b>固定する規則</b>: {@code derivedName} は channelType=GROUP_DM かつ明示名が null/空白のときだけ値を持つ。
 * 呼出ユーザー以外のメンバーを {@code joinedAt} 昇順・同値ならメンバー行 id 昇順で並べ、
 * {@code UserEntity.displayName} を {@code ", "} で連結する。先頭3名まで、4名以上は末尾に {@code " +N"}（N=残り人数）。
 * ソフトデリート済み（{@code UserRepository} が返さない）ユーザーは {@code "ユーザー"}。自分以外0名は null。
 * DB の name 列には書かない。ダッシュボード（チャットハブ）の GROUP_DM 表示名
 * {@code DirectMessageItemDto.derivedName} は「明示名 → 導出名 → null」。</p>
 *
 * <p><b>試験層</b>（TEST_CONVENTION.md §3.1.2）: {@link AbstractMySqlIntegrationTest} の MOCK +
 * {@code @AutoConfigureMockMvc}（{@code addFilters=false} を付けない＝実 JWT・実 Security フィルタ）・実 MySQL。
 * 退出・作成の実 commit を観測するため、テスト全体を {@code @Transactional} で包まず、fixture は別 TX で明示 commit し、
 * {@link #所有fixtureだけ片付ける()} で自分が作った行だけ消す。</p>
 *
 * <p>作成系の導出失敗時の全ロールバック（AC-16）は Bean 差し替えで context が分かれるため
 * {@code ChatGroupDmDerivedNameRollbackIT} に分けた。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261113-0110 GROUP_DM 導出表示名 derivedName 契約テスト（試練）")
class ChatGroupDmDerivedNameContractIT extends AbstractMySqlIntegrationTest {

    private static final String CHANNELS = "/api/v1/chat/channels";
    private static final String CHAT_HUB = "/api/v1/dashboard/chat-hub";
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 10, 0, 0);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuthTokenService tokens;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private OrganizationRepository organizations;
    @Autowired private ChatChannelRepository channelRepository;
    @Autowired private ChatChannelMemberRepository memberRepository;
    @PersistenceContext private EntityManager em;

    private final List<Long> ownedUsers = new ArrayList<>();
    private final Set<Long> ownedChannels = new LinkedHashSet<>();
    private final List<Long> ownedTeams = new ArrayList<>();
    private final List<Long> ownedOrganizations = new ArrayList<>();

    private Long me;
    private Long alice;
    private Long bob;
    private Long carol;
    private Long dave;
    private Long erin;
    private Long gone;
    private Long sameOrgOutsider;
    private Long otherOrgOutsider;
    private Long orgA;
    private Long orgB;
    private Long teamA;

    @BeforeEach
    void fixtureを別TXでコミットする() {
        tx(() -> {
            me = user("me", "導出ミー");
            alice = user("alice", "導出アリス");
            bob = user("bob", "導出ボブ");
            carol = user("carol", "導出キャロル");
            dave = user("dave", "導出デイブ");
            erin = user("erin", "導出エリン");
            gone = user("gone", "導出退会者");
            sameOrgOutsider = user("same-org", "同組織の非メンバー");
            otherOrgOutsider = user("other-org", "他組織の非メンバー");
            orgA = organization("導出試練組織A");
            orgB = organization("導出試練組織B");
            teamA = team("導出試練チーム");
            for (Long u : List.of(me, alice, bob, sameOrgOutsider)) {
                MembershipTestHelper.insertMembership(em, u, ScopeType.ORGANIZATION, orgA, RoleKind.MEMBER);
            }
            MembershipTestHelper.insertMembership(em, otherOrgOutsider, ScopeType.ORGANIZATION, orgB, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, me, ScopeType.TEAM, teamA, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, alice, ScopeType.TEAM, teamA, RoleKind.MEMBER);
            return null;
        });
    }

    @AfterEach
    void 所有fixtureだけ片付ける() {
        SecurityContextHolder.clearContext();
        if (ownedUsers.isEmpty()) {
            return;
        }
        String userIn = inClause(ownedUsers);
        // API が作成したチャンネル（invite-to-zimmer / conversations）も自分の fixture ユーザーが所属するものとして回収する
        ownedChannels.addAll(jdbc.queryForList(
                "SELECT DISTINCT channel_id FROM chat_channel_members WHERE user_id IN " + userIn, Long.class));
        ownedChannels.addAll(jdbc.queryForList(
                "SELECT id FROM chat_channels WHERE created_by IN " + userIn, Long.class));
        if (!ownedChannels.isEmpty()) {
            String chIn = inClause(new ArrayList<>(ownedChannels));
            jdbc.update("DELETE FROM chat_messages WHERE channel_id IN " + chIn);
            jdbc.update("DELETE FROM chat_channel_members WHERE channel_id IN " + chIn);
            jdbc.update("DELETE FROM chat_channels WHERE id IN " + chIn);
        }
        jdbc.update("DELETE FROM user_roles WHERE user_id IN " + userIn);
        jdbc.update("DELETE FROM memberships WHERE user_id IN " + userIn);
        jdbc.update("DELETE FROM users WHERE id IN " + userIn);
        for (Long t : ownedTeams) {
            jdbc.update("DELETE FROM teams WHERE id = ?", t);
        }
        for (Long o : ownedOrganizations) {
            jdbc.update("DELETE FROM organizations WHERE id = ?", o);
        }
        // MembershipTestHelper が共通 seed する roles は他の試練も共有するため削除しない。
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-1 / AC-7 / AC-12 作成系の応答
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-1/AC-12: invite-to-zimmer の 201 応答に derivedName・memberCount・viewer が入り、DB の name は null のまま")
    void ac1_inviteToZimmerの応答に導出名とmemberCountとviewerが入る() throws Exception {
        Long dm = channel(ChannelType.DM, null, null);
        member(dm, me, ChannelMemberRole.OWNER, T0);
        member(dm, alice, ChannelMemberRole.MEMBER, T0.plusSeconds(1));

        JsonNode zimmer = data(http(post(CHANNELS + "/{id}/invite-to-zimmer", dm)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userIds\":[" + carol + "," + bob + "],\"shareHistory\":false}"), me, 201));

        assertThat(zimmer.path("identity").path("channelType").asText()).isEqualTo("GROUP_DM");
        // Kabine メンバー（joinedAt 昇順: me, alice）→ 招待者（リクエスト順: carol, bob）の順に参加する
        assertThat(text(zimmer.path("derivedName"))).isEqualTo("導出アリス, 導出キャロル, 導出ボブ");
        assertThat(zimmer.path("memberCount").isNumber()).as("memberCount が数値で入る").isTrue();
        assertThat(zimmer.path("memberCount").asInt()).isEqualTo(4);
        assertThat(zimmer.path("viewer").isObject()).as("viewer が入る").isTrue();
        assertThat(zimmer.path("viewer").path("role").asText()).isEqualTo("OWNER");
        assertThat(dbName(zimmer.path("id").asLong())).as("AC-12: DB の name は導出名で更新されない").isNull();
    }

    @Test
    @DisplayName("AC-7/AC-12: conversations（自分＋2名＝3名以上で startZimmer）の 201 応答に derivedName が入る")
    void ac7_conversationsの応答に導出名が入る() throws Exception {
        JsonNode zimmer = data(http(post(CHANNELS + "/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userIds\":[" + bob + "," + alice + "]}"), me, 201));

        assertThat(zimmer.path("identity").path("channelType").asText()).isEqualTo("GROUP_DM");
        assertThat(text(zimmer.path("derivedName"))).isEqualTo("導出ボブ, 導出アリス");
        assertThat(dbName(zimmer.path("id").asLong())).as("AC-12").isNull();
    }

    @Test
    @DisplayName("AC-7/AC-12: convert-to-group の応答に derivedName が入る（変換後は GROUP_DM・名前なし）")
    void ac7_convertToGroupの応答に導出名が入る() throws Exception {
        Long dm = channel(ChannelType.DM, null, null);
        member(dm, me, ChannelMemberRole.OWNER, T0);
        member(dm, carol, ChannelMemberRole.MEMBER, T0.plusSeconds(1));

        JsonNode converted = data(http(post(CHANNELS + "/{id}/convert-to-group", dm), me, 200));

        assertThat(converted.path("identity").path("channelType").asText()).isEqualTo("GROUP_DM");
        assertThat(text(converted.path("derivedName"))).isEqualTo("導出キャロル");
        assertThat(dbName(dm)).as("AC-12").isNull();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-2 / AC-3 一覧・詳細の適用範囲
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-2/AC-12: 一覧で名前なし（null/空白）GROUP_DM だけに derivedName。DM・チームチャンネル・名前付き GROUP_DM は null")
    void ac2_一覧で名前なしGROUP_DMだけに導出名() throws Exception {
        Long unnamed = groupDm(null, me, alice, bob);
        Long blank = groupDm("   ", me, bob, carol);
        Long named = groupDm("名前付きZimmer", me, alice, bob);
        Long dm = channel(ChannelType.DM, null, null);
        member(dm, me, ChannelMemberRole.OWNER, T0);
        member(dm, carol, ChannelMemberRole.MEMBER, T0.plusSeconds(1));
        Long teamCh = channel(ChannelType.TEAM_PUBLIC, "導出試練チャンネル", teamA);
        member(teamCh, me, ChannelMemberRole.OWNER, T0);
        member(teamCh, alice, ChannelMemberRole.MEMBER, T0.plusSeconds(1));

        Map<Long, JsonNode> list = byId(data(http(get(CHANNELS), me, 200)));

        assertThat(text(list.get(unnamed).path("derivedName"))).isEqualTo("導出アリス, 導出ボブ");
        assertThat(text(list.get(blank).path("derivedName"))).as("空白名は名前なし扱い").isEqualTo("導出ボブ, 導出キャロル");
        assertThat(text(list.get(named).path("derivedName"))).as("名前付き GROUP_DM").isNull();
        assertThat(text(list.get(dm).path("derivedName"))).as("1対1 DM").isNull();
        assertThat(text(list.get(teamCh).path("derivedName"))).as("チームチャンネル").isNull();
        assertThat(dbName(unnamed)).as("AC-12: 一覧取得で DB の name は書き換わらない").isNull();
    }

    @Test
    @DisplayName("AC-3/AC-12: 詳細で同規則（名前なし GROUP_DM は導出名、名前付き・DM は null）")
    void ac3_詳細で同規則() throws Exception {
        Long unnamed = groupDm(null, me, alice, bob);
        Long named = groupDm("名前付きZimmer", me, alice, bob);
        Long dm = channel(ChannelType.DM, null, null);
        member(dm, me, ChannelMemberRole.OWNER, T0);
        member(dm, carol, ChannelMemberRole.MEMBER, T0.plusSeconds(1));

        assertThat(text(data(http(get(CHANNELS + "/{id}", unnamed), me, 200)).path("derivedName")))
                .isEqualTo("導出アリス, 導出ボブ");
        assertThat(text(data(http(get(CHANNELS + "/{id}", named), me, 200)).path("derivedName"))).isNull();
        assertThat(text(data(http(get(CHANNELS + "/{id}", dm), me, 200)).path("derivedName"))).isNull();
        assertThat(dbName(unnamed)).as("AC-12: 詳細取得で DB の name は書き換わらない").isNull();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-4 / AC-5 / AC-6 連結規則
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-4: 自分以外ちょうど3名は \"+N\" なし、4名は \"A, B, C +1\"、5名は \"+2\"")
    void ac4_先頭3名と残り人数() throws Exception {
        Long three = groupDm(null, me, alice, bob, carol);
        Long four = groupDm(null, me, alice, bob, carol, dave);
        Long five = groupDm(null, me, alice, bob, carol, dave, erin);

        assertThat(derivedOf(three, me)).isEqualTo("導出アリス, 導出ボブ, 導出キャロル");
        assertThat(derivedOf(four, me)).isEqualTo("導出アリス, 導出ボブ, 導出キャロル +1");
        assertThat(derivedOf(five, me)).isEqualTo("導出アリス, 導出ボブ, 導出キャロル +2");
    }

    @Test
    @DisplayName("AC-5: 自分以外0名の GROUP_DM は derivedName=null で、詳細・一覧とも 500 にならない")
    void ac5_自分以外0名はnullで500にならない() throws Exception {
        Long solo = groupDm(null, me);
        Long other = groupDm(null, me, alice);

        JsonNode detail = data(http(get(CHANNELS + "/{id}", solo), me, 200));
        assertThat(text(detail.path("derivedName"))).isNull();
        Map<Long, JsonNode> list = byId(data(http(get(CHANNELS), me, 200)));
        assertThat(list).containsKey(solo);
        assertThat(text(list.get(solo).path("derivedName"))).isNull();
        // 同じ一覧の他の名前なし GROUP_DM は導出されている（0名の扱いが一覧全体を壊さない）
        assertThat(text(list.get(other).path("derivedName"))).isEqualTo("導出アリス");
    }

    @Test
    @DisplayName("AC-6: 退会（ソフトデリート）ユーザーは \"ユーザー\" と表示され、並び順は参加順のまま")
    void ac6_退会ユーザーはユーザーと表示() throws Exception {
        Long ch = channel(ChannelType.GROUP_DM, null, null);
        member(ch, me, ChannelMemberRole.OWNER, T0);
        member(ch, gone, ChannelMemberRole.MEMBER, T0.plusSeconds(1));
        member(ch, alice, ChannelMemberRole.MEMBER, T0.plusSeconds(2));
        jdbc.update("UPDATE users SET deleted_at = NOW() WHERE id = ?", gone);

        assertThat(derivedOf(ch, me)).isEqualTo("ユーザー, 導出アリス");
        Map<Long, JsonNode> list = byId(data(http(get(CHANNELS), me, 200)));
        assertThat(text(list.get(ch).path("derivedName"))).isEqualTo("ユーザー, 導出アリス");
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-8 / AC-9 / AC-10 per-user と認可
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-8/AC-12: 退出後の取得で退出者が導出名から消え、DB の name は null のまま")
    void ac8_退出者が導出名から消える() throws Exception {
        Long ch = groupDm(null, me, alice, bob);
        assertThat(derivedOf(ch, me)).isEqualTo("導出アリス, 導出ボブ");

        http(delete(CHANNELS + "/{id}/members/{uid}", ch, alice), alice, 204);

        assertThat(derivedOf(ch, me)).isEqualTo("導出ボブ");
        assertThat(dbName(ch)).isNull();
    }

    @Test
    @DisplayName("AC-9: 別メンバーが取得するとその人自身が除外される（詳細・一覧）")
    void ac9_取得者自身が除外される() throws Exception {
        Long ch = groupDm(null, me, alice, bob);

        assertThat(derivedOf(ch, me)).isEqualTo("導出アリス, 導出ボブ");
        assertThat(derivedOf(ch, alice)).isEqualTo("導出ミー, 導出ボブ");
        assertThat(derivedOf(ch, bob)).isEqualTo("導出ミー, 導出アリス");
        Map<Long, JsonNode> bobList = byId(data(http(get(CHANNELS), bob, 200)));
        assertThat(text(bobList.get(ch).path("derivedName"))).isEqualTo("導出ミー, 導出アリス");
    }

    @Test
    @DisplayName("AC-10: 非メンバー（同組織・他組織）の詳細は 403/404 で本文に導出名を含まず、一覧にも出ない")
    void ac10_非メンバーには導出名が漏れない() throws Exception {
        Long ch = groupDm(null, me, alice, bob);
        // 前提: メンバーには導出名が出る（漏れていないことの比較対象）
        assertThat(derivedOf(ch, me)).isEqualTo("導出アリス, 導出ボブ");

        for (Long outsider : List.of(sameOrgOutsider, otherOrgOutsider)) {
            MvcResult res = mvc.perform(auth(get(CHANNELS + "/{id}", ch), outsider)).andReturn();
            assertThat(res.getResponse().getStatus()).as("非メンバー " + outsider + " の詳細").isIn(403, 404);
            String body = res.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).doesNotContain("導出アリス").doesNotContain("導出ボブ").doesNotContain("導出ミー");
            assertThat(body).doesNotContain("\"derivedName\":\"");

            Map<Long, JsonNode> list = byId(data(http(get(CHANNELS), outsider, 200)));
            assertThat(list).as("非メンバー " + outsider + " の一覧").doesNotContainKey(ch);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-11 クエリ数
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-11: 一覧で名前なし GROUP_DM が 10 件でも導出のための SQL 数が件数に比例しない（1件と同数）")
    void ac11_一覧のSQL数がGROUP_DM件数に比例しない() throws Exception {
        Long smallViewer = tx(() -> user("n1-small", "N1小"));
        Long largeViewer = tx(() -> user("n1-large", "N1大"));
        Long smallA = tx(() -> user("n1-sa", "N1小相手A"));
        Long smallB = tx(() -> user("n1-sb", "N1小相手B"));
        Long smallCh = groupDm(null, smallViewer, smallA, smallB);
        List<Long> largeChannels = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            int n = i;
            Long a = tx(() -> user("n1-la" + n, "N1大相手A" + n));
            Long b = tx(() -> user("n1-lb" + n, "N1大相手B" + n));
            largeChannels.add(groupDm(null, largeViewer, a, b));
            expected.add("N1大相手A" + n + ", N1大相手B" + n);
        }

        // 初回呼出のキャッシュ・JWT 周りの揺れを除くため、両者とも一度温めてから計測する
        http(get(CHANNELS), smallViewer, 200);
        http(get(CHANNELS), largeViewer, 200);
        Statistics stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);

        stats.clear();
        Map<Long, JsonNode> small = byId(data(http(get(CHANNELS), smallViewer, 200)));
        long smallCount = stats.getPrepareStatementCount();
        stats.clear();
        Map<Long, JsonNode> large = byId(data(http(get(CHANNELS), largeViewer, 200)));
        long largeCount = stats.getPrepareStatementCount();
        System.out.println("CMP-261113-0110 AC-11 chat-channels list SQL small=" + smallCount + " large=" + largeCount);

        assertThat(text(small.get(smallCh).path("derivedName"))).isEqualTo("N1小相手A, N1小相手B");
        for (int i = 0; i < 10; i++) {
            assertThat(text(large.get(largeChannels.get(i)).path("derivedName"))).isEqualTo(expected.get(i));
        }
        assertThat(largeCount).as("GROUP_DM 件数に依存しない SQL 数").isEqualTo(smallCount);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-14 ダッシュボード
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-14: ダッシュボードの GROUP_DM 表示名は 名前付き→明示名／名前なし→導出名／自分以外0名→null。DM は null、partnerDisplayName は従来どおり")
    void ac14_ダッシュボードのGROUP_DM表示名() throws Exception {
        Long unnamed = groupDm(null, me, alice, bob);
        Long named = groupDm("名前付きZimmer", me, alice, bob);
        Long solo = groupDm(null, me);
        Long dm = channel(ChannelType.DM, null, null);
        member(dm, me, ChannelMemberRole.OWNER, T0);
        member(dm, carol, ChannelMemberRole.MEMBER, T0.plusSeconds(1));

        Map<Long, JsonNode> hub = hubById(me);

        assertThat(text(hub.get(unnamed).path("derivedName"))).isEqualTo("導出アリス, 導出ボブ");
        assertThat(text(hub.get(named).path("derivedName"))).isEqualTo("名前付きZimmer");
        assertThat(text(hub.get(solo).path("derivedName"))).isNull();
        assertThat(text(hub.get(dm).path("derivedName"))).as("1対1 DM は従来の partner* を使う").isNull();
        // 既存 partnerDisplayName は後方互換のため従来どおり「自分以外の先頭1名の lastName + 半角空白 + firstName」
        assertThat(hub.get(unnamed).path("partnerDisplayName").asText()).isEqualTo("試練 alice");
        assertThat(hub.get(dm).path("partnerDisplayName").asText()).isEqualTo("試練 carol");
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-15 並び順の一貫性
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-15: joinedAt 同値のメンバーはメンバー行 id 昇順で、一覧・詳細・ダッシュボードで同一の並び")
    void ac15_joinedAt同値はメンバー行id昇順で全経路一致() throws Exception {
        // メンバー行の挿入順（carol → alice → bob）を、ユーザー id 順（alice < bob < carol）とも表示名の辞書順とも変える
        Long tie = channel(ChannelType.GROUP_DM, null, null);
        member(tie, me, ChannelMemberRole.OWNER, T0);
        member(tie, carol, ChannelMemberRole.MEMBER, T0.plusSeconds(5));
        member(tie, alice, ChannelMemberRole.MEMBER, T0.plusSeconds(5));
        member(tie, bob, ChannelMemberRole.MEMBER, T0.plusSeconds(5));
        // joinedAt が異なる場合は joinedAt が優先（メンバー行 id 順 alice → bob → carol と逆転させる）
        Long ordered = channel(ChannelType.GROUP_DM, null, null);
        member(ordered, me, ChannelMemberRole.OWNER, T0);
        member(ordered, alice, ChannelMemberRole.MEMBER, T0.plusSeconds(30));
        member(ordered, bob, ChannelMemberRole.MEMBER, T0.plusSeconds(10));
        member(ordered, carol, ChannelMemberRole.MEMBER, T0.plusSeconds(20));

        String tieExpected = "導出キャロル, 導出アリス, 導出ボブ";
        String orderedExpected = "導出ボブ, 導出キャロル, 導出アリス";
        Map<Long, JsonNode> list = byId(data(http(get(CHANNELS), me, 200)));
        Map<Long, JsonNode> hub = hubById(me);

        assertThat(derivedOf(tie, me)).as("詳細").isEqualTo(tieExpected);
        assertThat(text(list.get(tie).path("derivedName"))).as("一覧").isEqualTo(tieExpected);
        assertThat(text(hub.get(tie).path("derivedName"))).as("ダッシュボード").isEqualTo(tieExpected);
        assertThat(derivedOf(ordered, me)).as("詳細").isEqualTo(orderedExpected);
        assertThat(text(list.get(ordered).path("derivedName"))).as("一覧").isEqualTo(orderedExpected);
        assertThat(text(hub.get(ordered).path("derivedName"))).as("ダッシュボード").isEqualTo(orderedExpected);
    }

    @Test
    @DisplayName("AC-15: 作成応答（同一秒に参加した招待者）と一覧・詳細・ダッシュボードの並びが同一")
    void ac15_作成応答と以降の取得で並びが同一() throws Exception {
        // 同一リクエスト内で作られるメンバー行は joinedAt が秒精度で同値になりうる → メンバー行 id（=リクエスト順）で並ぶ
        JsonNode created = data(http(post(CHANNELS + "/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userIds\":[" + carol + "," + alice + "," + bob + "]}"), me, 201));
        Long id = created.path("id").asLong();
        String expected = "導出キャロル, 導出アリス, 導出ボブ";

        assertThat(text(created.path("derivedName"))).as("作成応答").isEqualTo(expected);
        assertThat(derivedOf(id, me)).as("詳細").isEqualTo(expected);
        assertThat(text(byId(data(http(get(CHANNELS), me, 200))).get(id).path("derivedName"))).as("一覧").isEqualTo(expected);
        assertThat(text(hubById(me).get(id).path("derivedName"))).as("ダッシュボード").isEqualTo(expected);
    }

    // ═════════════════════════════════════════════════════════════════════
    // helpers
    // ═════════════════════════════════════════════════════════════════════

    private String derivedOf(Long channelId, Long actor) throws Exception {
        return text(data(http(get(CHANNELS + "/{id}", channelId), actor, 200)).path("derivedName"));
    }

    private Map<Long, JsonNode> hubById(Long actor) throws Exception {
        Map<Long, JsonNode> result = new java.util.HashMap<>();
        for (JsonNode n : data(http(get(CHAT_HUB), actor, 200)).path("directMessages")) {
            result.put(n.path("channelId").asLong(), n);
        }
        return result;
    }

    private Map<Long, JsonNode> byId(JsonNode array) {
        Map<Long, JsonNode> result = new java.util.HashMap<>();
        for (JsonNode n : array) {
            result.put(n.path("id").asLong(), n);
        }
        return result;
    }

    /** JSON の null / 欠落を Java の null に揃える。 */
    private static String text(JsonNode node) {
        return (node == null || node.isMissingNode() || node.isNull()) ? null : node.asText();
    }

    private String dbName(Long channelId) {
        return jdbc.queryForObject("SELECT name FROM chat_channels WHERE id = ?", String.class, channelId);
    }

    private JsonNode data(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private MvcResult http(MockHttpServletRequestBuilder request, Long actor, int expectedStatus) throws Exception {
        MvcResult res = mvc.perform(auth(request, actor)).andReturn();
        assertThat(res.getResponse().getStatus())
                .as("HTTP status: " + res.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(expectedStatus);
        return res;
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, Long actor) {
        return request.header("Authorization", "Bearer " + tokens.issueAccessToken(actor, List.of("MEMBER")));
    }

    /** 名前（null 可）付き GROUP_DM を作り、先頭を OWNER、以降を 1 秒ずつ遅い joinedAt で参加させる。 */
    private Long groupDm(String name, Long... membersInJoinOrder) {
        Long ch = channel(ChannelType.GROUP_DM, name, null);
        for (int i = 0; i < membersInJoinOrder.length; i++) {
            member(ch, membersInJoinOrder[i], i == 0 ? ChannelMemberRole.OWNER : ChannelMemberRole.MEMBER,
                    T0.plusSeconds(i));
        }
        return ch;
    }

    private Long channel(ChannelType type, String name, Long teamId) {
        Long id = tx(() -> channelRepository.save(ChatChannelEntity.builder()
                .channelType(type).name(name).teamId(teamId).createdBy(me).build()).getId());
        ownedChannels.add(id);
        return id;
    }

    private void member(Long channelId, Long userId, ChannelMemberRole role, LocalDateTime joinedAt) {
        tx(() -> memberRepository.save(ChatChannelMemberEntity.builder()
                .channelId(channelId).userId(userId).role(role).joinedAt(joinedAt).build()));
    }

    private Long user(String label, String displayName) {
        Long id = users.save(UserEntity.builder()
                .email("cmp0110-" + label + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com")
                .lastName("試練").firstName(label).displayName(displayName).isSearchable(false)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo").build()).getId();
        ownedUsers.add(id);
        return id;
    }

    private Long organization(String name) {
        Long id = organizations.save(OrganizationEntity.builder()
                .slug("cmp0110-" + UUID.randomUUID().toString().substring(0, 8)).name(name)
                .orgType(OrganizationEntity.OrgType.OTHER).visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(false).build()).getId();
        ownedOrganizations.add(id);
        return id;
    }

    private Long team(String name) {
        Long id = teams.save(TeamEntity.builder().slug("cmp0110-" + UUID.randomUUID().toString().substring(0, 8))
                .name(name).visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(false).build()).getId();
        ownedTeams.add(id);
        return id;
    }

    private static String inClause(List<Long> ids) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ids.get(i));
        }
        return sb.append(')').toString();
    }

    private <T> T tx(Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(status -> work.get());
    }
}
