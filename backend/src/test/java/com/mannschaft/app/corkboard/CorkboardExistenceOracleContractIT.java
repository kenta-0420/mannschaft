package com.mannschaft.app.corkboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.corkboard.repository.CorkboardCardRepository;
import com.mannschaft.app.corkboard.repository.CorkboardGroupRepository;
import com.mannschaft.app.corkboard.repository.CorkboardRepository;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * CMP-260820-1422（存在オラクル一掃）corkboard 残件の API 契約テスト。
 *
 * <p>契約: 閲覧権限のない者（非メンバー・他チーム/他組織の者・他人の個人ボード）が
 * 実在 boardId を指定したときの応答は、不在 boardId を指定したときと
 * <b>status・Content-Type・本文全体（error.code・message 含む）まで同一</b>の 404 {@code CORKBOARD_001} とする。
 * 是正前は scope 内に実在するが非メンバーなら 403 {@code CORKBOARD_009}、不在なら 404 で、
 * boardId の実在が外部から判別できた（存在オラクル）。</p>
 *
 * <p>メンバー（＝ボードを閲覧できる者）の権限不足 403 {@code CORKBOARD_009} は維持する。
 * メンバーは当該ボードを一覧・詳細で既に閲覧できるため、403 を返しても新たに漏れる存在情報はない。</p>
 *
 * <p>実JWT・実Securityフィルタ・実MySQL で検査する（TEST_CONVENTION §3.1.2。認可を検査するため
 * {@code addFilters=false} を使わない）。fixture は {@link CorkboardTestFixture} ＋ Repository 経由（所属・権限ロール・roles も含む）で独立 commit し、所有行だけを後始末する。
 * 拒否された要求の DB 不変は、所有ボード・カード・セクションの全列スナップショット（{@code deleted_at} 含む）の
 * 前後一致で確かめる。</p>
 *
 * <p>攻め口: 空（不在ID）／認可（他チーム・他組織・非メンバー・他人の個人ボード・未認証401）／
 * 途中失敗（拒否された更新・削除・作成で DB が変わらないこと）。</p>
 */
@AutoConfigureMockMvc(addFilters = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("corkboard 存在オラクル契約（越境IDは不在IDと同一の404・実JWT/filter）")
class CorkboardExistenceOracleContractIT extends AbstractMySqlIntegrationTest {

    /** 確実に存在しない boardId。 */
    private static final long MISSING_ID = Long.MAX_VALUE;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AuthTokenService tokens;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private OrganizationRepository organizations;
    @Autowired private CorkboardRepository boards;
    @Autowired private CorkboardCardRepository cards;
    @Autowired private CorkboardGroupRepository groups;
    @Autowired private MembershipRepository memberships;
    @Autowired private UserRoleRepository userRoles;
    @Autowired private RoleRepository roles;
    @PersistenceContext private EntityManager em;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> teamIds = new ArrayList<>();
    private final List<Long> orgIds = new ArrayList<>();
    private final List<Long> boardIds = new ArrayList<>();
    private final List<Long> membershipIds = new ArrayList<>();
    private final List<Long> userRoleIds = new ArrayList<>();
    private final List<Long> createdRoleIds = new ArrayList<>();

    private Long teamAId;
    private Long orgOId;
    private Long adminBId;
    private Long memberAId;
    private Long orgPMemberId;
    private Long outsiderId;

    private Long teamBoardId;
    private Long teamAllMembersBoardId;
    private Long orgBoardId;
    private Long personalBoardId;
    private Long cardId;
    private Long groupId;

    @BeforeEach
    void Repository経由で所有行をcommitする() {
        var values = mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        tx(() -> {
            teamAId = saveTeam("コルク契約チームA");
            Long teamBId = saveTeam("コルク契約チームB");
            orgOId = saveOrganization("コルク契約組織O");
            Long orgPId = saveOrganization("コルク契約組織P");

            Long adminAId = saveUser("admin-a");
            adminBId = saveUser("admin-b");
            memberAId = saveUser("member-a");
            Long orgOMemberId = saveUser("org-o");
            orgPMemberId = saveUser("org-p");
            outsiderId = saveUser("outsider");

            Long adminRoleId = ensureRole("ADMIN");
            ensureRole("MEMBER");
            saveUserRole(adminAId, adminRoleId, teamAId);
            saveMembership(adminAId, ScopeType.TEAM, teamAId);
            saveUserRole(adminBId, adminRoleId, teamBId);
            saveMembership(adminBId, ScopeType.TEAM, teamBId);
            saveMembership(memberAId, ScopeType.TEAM, teamAId);
            saveMembership(orgOMemberId, ScopeType.ORGANIZATION, orgOId);
            saveMembership(orgPMemberId, ScopeType.ORGANIZATION, orgPId);

            teamBoardId = saveBoard(CorkboardTestFixture.scopedBoard("TEAM", teamAId, "ADMIN_ONLY"));
            teamAllMembersBoardId = saveBoard(CorkboardTestFixture.scopedBoard("TEAM", teamAId, "ALL_MEMBERS"));
            orgBoardId = saveBoard(CorkboardTestFixture.scopedBoard("ORGANIZATION", orgOId, "ADMIN_ONLY"));
            personalBoardId = saveBoard(CorkboardTestFixture.personalBoard(memberAId));
            cardId = cards.save(CorkboardTestFixture.memoCard(teamAllMembersBoardId, memberAId)).getId();
            groupId = groups.save(CorkboardTestFixture.group(teamAllMembersBoardId)).getId();

            assertThat(boards.existsById(MISSING_ID)).isFalse();
            return null;
        });
    }

    @AfterEach
    void 所有行だけ削除して共有認証状態を復元する() {
        try {
            tx(() -> {
                if (!boardIds.isEmpty()) {
                    named("DELETE FROM corkboard_cards WHERE corkboard_id IN (:ids)", boardIds);
                    named("DELETE FROM corkboard_groups WHERE corkboard_id IN (:ids)", boardIds);
                    named("DELETE FROM corkboards WHERE id IN (:ids)", boardIds);
                }
                memberships.deleteAllById(membershipIds);
                userRoles.deleteAllById(userRoleIds);
                em.flush();
                roles.deleteAllById(createdRoleIds);
                teams.deleteAllById(teamIds);
                organizations.deleteAllById(orgIds);
                em.flush();
                users.deleteAllById(userIds);
                return null;
            });
        } finally {
            try { reset(redisTemplate); } finally { SecurityContextHolder.clearContext(); }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // チームボード（/teams/{teamId}/corkboards/{id}）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("チームボード 詳細・更新・削除")
    class TeamBoard {

        @Test
        @DisplayName("非メンバーの詳細取得: 実在IDと不在IDで応答が同一の404")
        void 非メンバー_詳細_実在と不在が同一() throws Exception {
            assertSameAsMissing(outsiderId,
                    get("/api/v1/teams/{t}/corkboards/{id}", teamAId, teamBoardId),
                    get("/api/v1/teams/{t}/corkboards/{id}", teamAId, MISSING_ID));
        }

        @Test
        @DisplayName("非メンバーの更新: 実在IDと不在IDで応答が同一の404・DB不変")
        void 非メンバー_更新_実在と不在が同一() throws Exception {
            assertSameAsMissing(outsiderId,
                    jsonBody(put("/api/v1/teams/{t}/corkboards/{id}", teamAId, teamBoardId), "{\"name\":\"乗っ取り\"}"),
                    jsonBody(put("/api/v1/teams/{t}/corkboards/{id}", teamAId, MISSING_ID), "{\"name\":\"乗っ取り\"}"));
        }

        @Test
        @DisplayName("非メンバーの削除: 実在IDと不在IDで応答が同一の404・論理削除されない（DB不変）")
        void 非メンバー_削除_実在と不在が同一() throws Exception {
            assertSameAsMissing(outsiderId,
                    delete("/api/v1/teams/{t}/corkboards/{id}", teamAId, teamBoardId),
                    delete("/api/v1/teams/{t}/corkboards/{id}", teamAId, MISSING_ID));
        }

        @Test
        @DisplayName("他チームADMINが他チームのパスで実在IDを指定: 不在IDと同一の404・DB不変")
        void 他チームADMIN_他チームパス_実在と不在が同一() throws Exception {
            assertSameAsMissing(adminBId,
                    jsonBody(put("/api/v1/teams/{t}/corkboards/{id}", teamAId, teamBoardId), "{\"name\":\"越境\"}"),
                    jsonBody(put("/api/v1/teams/{t}/corkboards/{id}", teamAId, MISSING_ID), "{\"name\":\"越境\"}"));
        }

        @Test
        @DisplayName("他チームADMINが他チームの実在IDを削除: 不在IDと同一の404・DB不変")
        void 他チームADMIN_削除_実在と不在が同一() throws Exception {
            assertSameAsMissing(adminBId,
                    delete("/api/v1/teams/{t}/corkboards/{id}", teamAId, teamBoardId),
                    delete("/api/v1/teams/{t}/corkboards/{id}", teamAId, MISSING_ID));
        }

        @Test
        @DisplayName("メンバーの権限不足は403を維持（メンバーはボードを閲覧でき、存在情報は新たに漏れない）・DB不変")
        void メンバーの権限不足は403維持() throws Exception {
            String before = snapshot();
            assertError(perform(memberAId, jsonBody(put("/api/v1/teams/{t}/corkboards/{id}", teamAId, teamBoardId),
                    "{\"name\":\"改題\"}")), 403, "CORKBOARD_009");
            assertError(perform(memberAId, delete("/api/v1/teams/{t}/corkboards/{id}", teamAId, teamBoardId)),
                    403, "CORKBOARD_009");
            assertThat(snapshot()).isEqualTo(before);
        }

        @Test
        @DisplayName("未認証は実filterで実在IDも不在IDも同一の401・DB不変")
        void 未認証は実filterで401() throws Exception {
            String before = snapshot();
            for (MockHttpServletRequestBuilder[] pair : List.of(
                    new MockHttpServletRequestBuilder[] {
                            get("/api/v1/teams/{t}/corkboards/{id}", teamAId, teamBoardId),
                            get("/api/v1/teams/{t}/corkboards/{id}", teamAId, MISSING_ID)},
                    new MockHttpServletRequestBuilder[] {
                            delete("/api/v1/teams/{t}/corkboards/{id}", teamAId, teamBoardId),
                            delete("/api/v1/teams/{t}/corkboards/{id}", teamAId, MISSING_ID)},
                    new MockHttpServletRequestBuilder[] {
                            get("/api/v1/corkboards/{id}", personalBoardId),
                            get("/api/v1/corkboards/{id}", MISSING_ID)})) {
                MvcResult actual = perform(null, pair[0]);
                MvcResult missing = perform(null, pair[1]);
                assertThat(actual.getResponse().getStatus()).isEqualTo(401);
                assertThat(missing.getResponse().getStatus()).isEqualTo(401);
                assertThat(actual.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .isEqualTo(missing.getResponse().getContentAsString(StandardCharsets.UTF_8));
            }
            assertThat(snapshot()).isEqualTo(before);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 組織ボード（/organizations/{orgId}/corkboards/{boardId}）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("組織ボード 詳細")
    class OrganizationBoard {

        @Test
        @DisplayName("他組織メンバーの詳細取得: 実在IDと不在IDで応答が同一の404")
        void 他組織メンバー_実在と不在が同一() throws Exception {
            assertSameAsMissing(orgPMemberId,
                    get("/api/v1/organizations/{o}/corkboards/{id}", orgOId, orgBoardId),
                    get("/api/v1/organizations/{o}/corkboards/{id}", orgOId, MISSING_ID));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // boardId 単独参照（/corkboards/{boardId}）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("boardId 単独参照")
    class Lookup {

        @Test
        @DisplayName("非メンバーがチームボードを参照: 実在IDと不在IDで応答が同一の404")
        void 非メンバー_チームボード_実在と不在が同一() throws Exception {
            assertSameAsMissing(outsiderId,
                    get("/api/v1/corkboards/{id}", teamBoardId),
                    get("/api/v1/corkboards/{id}", MISSING_ID));
        }

        @Test
        @DisplayName("他組織メンバーが組織ボードを参照: 実在IDと不在IDで応答が同一の404")
        void 他組織メンバー_組織ボード_実在と不在が同一() throws Exception {
            assertSameAsMissing(orgPMemberId,
                    get("/api/v1/corkboards/{id}", orgBoardId),
                    get("/api/v1/corkboards/{id}", MISSING_ID));
        }

        @Test
        @DisplayName("他人の個人ボードを参照: 実在IDと不在IDで応答が同一の404")
        void 他人の個人ボード_実在と不在が同一() throws Exception {
            assertSameAsMissing(outsiderId,
                    get("/api/v1/corkboards/{id}", personalBoardId),
                    get("/api/v1/corkboards/{id}", MISSING_ID));
        }

        @Test
        @DisplayName("正常系: メンバーの参照は200")
        void メンバーは200() throws Exception {
            MvcResult result = perform(memberAId, get("/api/v1/corkboards/{id}", teamBoardId));
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(json(result).path("data").path("id").asLong()).isEqualTo(teamBoardId);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // カード・セクション（/corkboards/{boardId}/cards・/groups）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("カード・セクション操作")
    class CardsAndGroups {

        @Test
        @DisplayName("非メンバーのカード作成: 実在IDと不在IDで応答が同一の404・DB不変")
        void 非メンバー_カード作成_実在と不在が同一() throws Exception {
            assertSameAsMissing(outsiderId,
                    jsonBody(post("/api/v1/corkboards/{id}/cards", teamAllMembersBoardId), "{\"cardType\":\"MEMO\"}"),
                    jsonBody(post("/api/v1/corkboards/{id}/cards", MISSING_ID), "{\"cardType\":\"MEMO\"}"));
        }

        @Test
        @DisplayName("他チームADMINのカード削除: 実在IDと不在IDで応答が同一の404・論理削除されない（DB不変）")
        void 他チームADMIN_カード削除_実在と不在が同一() throws Exception {
            assertSameAsMissing(adminBId,
                    delete("/api/v1/corkboards/{b}/cards/{c}", teamAllMembersBoardId, cardId),
                    delete("/api/v1/corkboards/{b}/cards/{c}", MISSING_ID, cardId));
            assertThat(tx(() -> jdbc.queryForObject(
                    "SELECT deleted_at IS NULL FROM corkboard_cards WHERE id = ?", Boolean.class, cardId))).isTrue();
        }

        @Test
        @DisplayName("非メンバーのセクション削除: 実在IDと不在IDで応答が同一の404・DB不変")
        void 非メンバー_セクション削除_実在と不在が同一() throws Exception {
            assertSameAsMissing(outsiderId,
                    delete("/api/v1/corkboards/{b}/groups/{g}", teamAllMembersBoardId, groupId),
                    delete("/api/v1/corkboards/{b}/groups/{g}", MISSING_ID, groupId));
        }

        @Test
        @DisplayName("他人の個人ボードへのカード作成: 実在IDと不在IDで応答が同一の404・DB不変")
        void 他人の個人ボード_カード作成_実在と不在が同一() throws Exception {
            assertSameAsMissing(outsiderId,
                    jsonBody(post("/api/v1/corkboards/{id}/cards", personalBoardId), "{\"cardType\":\"MEMO\"}"),
                    jsonBody(post("/api/v1/corkboards/{id}/cards", MISSING_ID), "{\"cardType\":\"MEMO\"}"));
        }

        @Test
        @DisplayName("ADMIN_ONLYボードでのメンバーのカード作成は403を維持（メンバーはボードを閲覧できる）・DB不変")
        void メンバーの権限不足は403維持() throws Exception {
            String before = snapshot();
            assertError(perform(memberAId, jsonBody(post("/api/v1/corkboards/{id}/cards", teamBoardId),
                    "{\"cardType\":\"MEMO\"}")), 403, "CORKBOARD_009");
            assertThat(snapshot()).isEqualTo(before);
        }

        @Test
        @DisplayName("正常系: ALL_MEMBERSボードでのメンバーのカード作成は201")
        void メンバーのカード作成は201() throws Exception {
            MvcResult result = perform(memberAId, jsonBody(post("/api/v1/corkboards/{id}/cards", teamAllMembersBoardId),
                    "{\"cardType\":\"MEMO\",\"title\":\"コルク契約正常系\"}"));
            assertThat(result.getResponse().getStatus()).isEqualTo(201);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    /**
     * 実在IDへの要求と不在IDへの要求が、同一の 404 {@code CORKBOARD_001} かつ本文全体まで一致し、
     * 所有行（{@code deleted_at} を含む全列）が要求の前後で変わらないことを検証する。
     */
    private void assertSameAsMissing(Long actorId, MockHttpServletRequestBuilder actual,
                                     MockHttpServletRequestBuilder missing) throws Exception {
        String before = snapshot();
        try {
            MvcResult missingResult = perform(actorId, missing);
            assertError(missingResult, 404, "CORKBOARD_001");
            MvcResult actualResult = perform(actorId, actual);
            assertThat(actualResult.getResponse().getStatus())
                    .as("実在IDの status は不在IDと同一であること（存在オラクル）")
                    .isEqualTo(missingResult.getResponse().getStatus());
            assertThat(actualResult.getResponse().getContentType())
                    .isEqualTo(missingResult.getResponse().getContentType());
            assertThat(json(actualResult)).as("実在IDの本文は不在IDと同一であること（存在オラクル）")
                    .isEqualTo(json(missingResult));
        } finally {
            assertThat(snapshot()).as("拒否された要求で所有行が変わらないこと").isEqualTo(before);
        }
    }

    private void assertError(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(json(result).path("error").path("code").asText()).isEqualTo(code);
    }

    private MvcResult perform(Long actorId, MockHttpServletRequestBuilder request) throws Exception {
        request.header("Accept-Language", "ja");
        if (actorId != null) {
            request.header("Authorization", "Bearer " + tokens.issueAccessToken(actorId, List.of("USER")));
        }
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private MockHttpServletRequestBuilder jsonBody(MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** 所有ボード・カード・セクションの全列（{@code deleted_at} 含む）を文字列化したスナップショット。 */
    private String snapshot() {
        return tx(() -> String.valueOf(List.of(
                rows("SELECT * FROM corkboards WHERE id IN (:ids) ORDER BY id"),
                rows("SELECT * FROM corkboard_cards WHERE corkboard_id IN (:ids) ORDER BY id"),
                rows("SELECT * FROM corkboard_groups WHERE corkboard_id IN (:ids) ORDER BY id"))));
    }

    private List<Map<String, Object>> rows(String sql) {
        return new NamedParameterJdbcTemplate(jdbc).queryForList(sql, new MapSqlParameterSource("ids", boardIds));
    }

    private void named(String sql, List<Long> ids) {
        new NamedParameterJdbcTemplate(jdbc).update(sql, new MapSqlParameterSource("ids", ids));
    }

    private <T> T tx(Supplier<T> work) {
        TransactionTemplate template = new TransactionTemplate(transactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> work.get());
    }

    private Long saveUser(String label) {
        Long id = users.save(CorkboardTestFixture.user(label)).getId();
        userIds.add(id);
        return id;
    }

    private Long ensureRole(String name) {
        return roles.findByName(name).map(role -> role.getId()).orElseGet(() -> {
            Long id = roles.save(CorkboardTestFixture.role(name)).getId();
            createdRoleIds.add(id);
            return id;
        });
    }

    private void saveMembership(Long userId, ScopeType scopeType, Long scopeId) {
        membershipIds.add(memberships.save(CorkboardTestFixture.membership(userId, scopeType, scopeId)).getId());
    }

    private void saveUserRole(Long userId, Long roleId, Long teamId) {
        userRoleIds.add(userRoles.save(CorkboardTestFixture.userRole(userId, roleId, teamId, null)).getId());
    }

    private Long saveTeam(String name) {
        Long id = teams.save(CorkboardTestFixture.team(name)).getId();
        teamIds.add(id);
        return id;
    }

    private Long saveOrganization(String name) {
        Long id = organizations.save(CorkboardTestFixture.organization(name)).getId();
        orgIds.add(id);
        return id;
    }

    private Long saveBoard(com.mannschaft.app.corkboard.entity.CorkboardEntity board) {
        Long id = boards.save(board).getId();
        boardIds.add(id);
        return id;
    }
}
