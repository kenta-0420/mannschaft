package com.mannschaft.app.corkboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260820-1422（存在オラクル一掃）corkboard 残件の API 契約テスト。
 *
 * <p>契約: 閲覧権限のない者（非メンバー・他チーム/他組織の者・他人の個人ボード）が
 * 実在 boardId を指定したときの応答は、不在 boardId を指定したときと
 * <b>status・error.code・error 本文（message 含む）まで同一</b>の 404 {@code CORKBOARD_001} とする。
 * 是正前は scope 内に実在するが非メンバーなら 403 {@code CORKBOARD_009}、不在なら 404 で、
 * boardId の実在が外部から判別できた（存在オラクル）。</p>
 *
 * <p>メンバー（＝ボードを閲覧できる者）の権限不足 403 {@code CORKBOARD_009} は維持する。
 * メンバーは当該ボードを一覧・詳細で既に見られるため、403 を返しても新たに漏れる存在情報はない。</p>
 *
 * <p>攻め口: 空（不在ID）／認可（他チーム・他組織・非メンバー・他人の個人ボード・未認証401）／
 * 途中失敗（拒否された更新・削除で DB が変わらないこと）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("corkboard 存在オラクル契約（越境IDは不在IDと同一の404）")
class CorkboardExistenceOracleContractIT extends AbstractMySqlIntegrationTest {

    /** 確実に存在しない boardId / cardId / groupId。 */
    private static final long MISSING_ID = 9_000_000_000L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long orgOId;
    private Long orgPId;
    private Long adminAId;
    private Long adminBId;
    private Long memberAId;
    private Long orgOMemberId;
    private Long orgPMemberId;
    private Long outsiderId;

    private Long teamBoardId;
    private Long teamAllMembersBoardId;
    private Long orgBoardId;
    private Long personalBoardId;
    private Long cardId;
    private Long groupId;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("CBオラクル チームA");
        teamBId = insertTeam("CBオラクル チームB");
        orgOId = insertOrganization("CBオラクル 組織O");
        orgPId = insertOrganization("CBオラクル 組織P");

        adminAId = insertUser("cb-oracle-admin-a@example.com");
        adminBId = insertUser("cb-oracle-admin-b@example.com");
        memberAId = insertUser("cb-oracle-member-a@example.com");
        orgOMemberId = insertUser("cb-oracle-org-o@example.com");
        orgPMemberId = insertUser("cb-oracle-org-p@example.com");
        outsiderId = insertUser("cb-oracle-outsider@example.com");

        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, orgOMemberId, ScopeType.ORGANIZATION, orgOId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, orgPMemberId, ScopeType.ORGANIZATION, orgPId, RoleKind.MEMBER);

        teamBoardId = insertBoard("TEAM", teamAId, "CBオラクル チームボード", "ADMIN_ONLY");
        teamAllMembersBoardId = insertBoard("TEAM", teamAId, "CBオラクル 全員編集ボード", "ALL_MEMBERS");
        orgBoardId = insertBoard("ORGANIZATION", orgOId, "CBオラクル 組織ボード", "ADMIN_ONLY");
        personalBoardId = insertPersonalBoard(memberAId, "CBオラクル 個人ボード");
        cardId = insertCard(teamAllMembersBoardId, memberAId);
        groupId = insertGroup(teamAllMembersBoardId);

        em.flush();
        em.clear();
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
        @DisplayName("非メンバーの更新: 実在IDと不在IDで応答が同一の404・ボード名は不変")
        void 非メンバー_更新_実在と不在が同一() throws Exception {
            String before = boardName(teamBoardId);
            assertSameAsMissing(outsiderId,
                    jsonBody(put("/api/v1/teams/{t}/corkboards/{id}", teamAId, teamBoardId), "{\"name\":\"乗っ取り\"}"),
                    jsonBody(put("/api/v1/teams/{t}/corkboards/{id}", teamAId, MISSING_ID), "{\"name\":\"乗っ取り\"}"));
            assertThat(boardName(teamBoardId)).isEqualTo(before);
        }

        @Test
        @DisplayName("非メンバーの削除: 実在IDと不在IDで応答が同一の404・論理削除されない")
        void 非メンバー_削除_実在と不在が同一() throws Exception {
            assertSameAsMissing(outsiderId,
                    delete("/api/v1/teams/{t}/corkboards/{id}", teamAId, teamBoardId),
                    delete("/api/v1/teams/{t}/corkboards/{id}", teamAId, MISSING_ID));
            assertThat(boardDeletedAtIsNull(teamBoardId)).isTrue();
        }

        @Test
        @DisplayName("他チームADMINが他チームのパスで実在IDを指定: 不在IDと同一の404")
        void 他チームADMIN_他チームパス_実在と不在が同一() throws Exception {
            assertSameAsMissing(adminBId,
                    jsonBody(put("/api/v1/teams/{t}/corkboards/{id}", teamAId, teamBoardId), "{\"name\":\"越境\"}"),
                    jsonBody(put("/api/v1/teams/{t}/corkboards/{id}", teamAId, MISSING_ID), "{\"name\":\"越境\"}"));
        }

        @Test
        @DisplayName("他チームADMINが自チームのパスで他チームの実在IDを指定: 不在IDと同一の404")
        void 他チームADMIN_自チームパス_実在と不在が同一() throws Exception {
            assertSameAsMissing(adminBId,
                    get("/api/v1/teams/{t}/corkboards/{id}", teamBId, teamBoardId),
                    get("/api/v1/teams/{t}/corkboards/{id}", teamBId, MISSING_ID));
        }

        @Test
        @DisplayName("メンバーの権限不足は403を維持（メンバーはボードを閲覧でき、存在情報は新たに漏れない）")
        void メンバーの権限不足は403維持() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(jsonBody(put("/api/v1/teams/{t}/corkboards/{id}", teamAId, teamBoardId),
                            "{\"name\":\"改題\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CORKBOARD_009"));
            mockMvc.perform(delete("/api/v1/teams/{t}/corkboards/{id}", teamAId, teamBoardId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CORKBOARD_009"));
        }

        @Test
        @DisplayName("未認証は実在IDでも不在IDでも401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/teams/{t}/corkboards/{id}", teamAId, teamBoardId))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/v1/teams/{t}/corkboards/{id}", teamAId, MISSING_ID))
                    .andExpect(status().isUnauthorized());
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

        @Test
        @DisplayName("正常系: 組織メンバーの詳細取得は200")
        void 組織メンバーは200() throws Exception {
            setAuthentication(orgOMemberId);
            mockMvc.perform(get("/api/v1/organizations/{o}/corkboards/{id}", orgOId, orgBoardId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(orgBoardId.intValue()));
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
            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/corkboards/{id}", teamBoardId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(teamBoardId.intValue()));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // カード・セクション（/corkboards/{boardId}/cards・/groups）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("カード・セクション操作")
    class CardsAndGroups {

        @Test
        @DisplayName("非メンバーのカード作成: 実在IDと不在IDで応答が同一の404・カードは増えない")
        void 非メンバー_カード作成_実在と不在が同一() throws Exception {
            long before = cardCount(teamAllMembersBoardId);
            assertSameAsMissing(outsiderId,
                    jsonBody(post("/api/v1/corkboards/{id}/cards", teamAllMembersBoardId), "{\"cardType\":\"MEMO\"}"),
                    jsonBody(post("/api/v1/corkboards/{id}/cards", MISSING_ID), "{\"cardType\":\"MEMO\"}"));
            assertThat(cardCount(teamAllMembersBoardId)).isEqualTo(before);
        }

        @Test
        @DisplayName("他チームADMINのカード削除: 実在IDと不在IDで応答が同一の404・カードは残る")
        void 他チームADMIN_カード削除_実在と不在が同一() throws Exception {
            assertSameAsMissing(adminBId,
                    delete("/api/v1/corkboards/{b}/cards/{c}", teamAllMembersBoardId, cardId),
                    delete("/api/v1/corkboards/{b}/cards/{c}", MISSING_ID, cardId));
            assertThat(cardExists(cardId)).isTrue();
        }

        @Test
        @DisplayName("非メンバーのセクション削除: 実在IDと不在IDで応答が同一の404・セクションは残る")
        void 非メンバー_セクション削除_実在と不在が同一() throws Exception {
            assertSameAsMissing(outsiderId,
                    delete("/api/v1/corkboards/{b}/groups/{g}", teamAllMembersBoardId, groupId),
                    delete("/api/v1/corkboards/{b}/groups/{g}", MISSING_ID, groupId));
            assertThat(groupExists(groupId)).isTrue();
        }

        @Test
        @DisplayName("他人の個人ボードへのカード作成: 実在IDと不在IDで応答が同一の404")
        void 他人の個人ボード_カード作成_実在と不在が同一() throws Exception {
            assertSameAsMissing(outsiderId,
                    jsonBody(post("/api/v1/corkboards/{id}/cards", personalBoardId), "{\"cardType\":\"MEMO\"}"),
                    jsonBody(post("/api/v1/corkboards/{id}/cards", MISSING_ID), "{\"cardType\":\"MEMO\"}"));
        }

        @Test
        @DisplayName("ADMIN_ONLYボードでのメンバーのカード作成は403を維持（メンバーはボードを閲覧できる）")
        void メンバーの権限不足は403維持() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(jsonBody(post("/api/v1/corkboards/{id}/cards", teamBoardId), "{\"cardType\":\"MEMO\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CORKBOARD_009"));
        }

        @Test
        @DisplayName("正常系: ALL_MEMBERSボードでのメンバーのカード作成は201")
        void メンバーのカード作成は201() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(jsonBody(post("/api/v1/corkboards/{id}/cards", teamAllMembersBoardId),
                            "{\"cardType\":\"MEMO\",\"title\":\"CBオラクル正常系\"}"))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    /**
     * 実在IDへの要求と不在IDへの要求が、同一の 404 {@code CORKBOARD_001} かつ error 本文まで一致することを検証する。
     */
    private void assertSameAsMissing(Long actorId, MockHttpServletRequestBuilder actual,
                                     MockHttpServletRequestBuilder missing) throws Exception {
        setAuthentication(actorId);
        MvcResult actualResult = mockMvc.perform(actual).andReturn();
        MvcResult missingResult = mockMvc.perform(missing).andReturn();

        JsonNode actualError = errorNode(actualResult);
        JsonNode missingError = errorNode(missingResult);
        assertThat(missingResult.getResponse().getStatus()).as("不在IDの応答").isEqualTo(404);
        assertThat(missingError.path("code").asText()).as("不在IDの error.code").isEqualTo("CORKBOARD_001");
        assertThat(actualResult.getResponse().getStatus())
                .as("実在IDの status は不在IDと同一であること（存在オラクル）")
                .isEqualTo(missingResult.getResponse().getStatus());
        assertThat(actualError).as("実在IDの error 本文は不在IDと同一であること（存在オラクル）")
                .isEqualTo(missingError);
    }

    private JsonNode errorNode(MvcResult result) throws Exception {
        byte[] body = result.getResponse().getContentAsByteArray();
        if (body.length == 0) {
            return objectMapper.nullNode();
        }
        return objectMapper.readTree(body).path("error");
    }

    private MockHttpServletRequestBuilder jsonBody(MockHttpServletRequestBuilder builder, String json) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private String boardName(Long boardId) {
        em.flush();
        em.clear();
        return (String) em.createNativeQuery("SELECT name FROM corkboards WHERE id = :id")
                .setParameter("id", boardId)
                .getSingleResult();
    }

    private boolean boardDeletedAtIsNull(Long boardId) {
        em.flush();
        em.clear();
        return em.createNativeQuery("SELECT deleted_at FROM corkboards WHERE id = :id")
                .setParameter("id", boardId)
                .getSingleResult() == null;
    }

    private long cardCount(Long boardId) {
        em.flush();
        em.clear();
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM corkboard_cards WHERE corkboard_id = :id")
                .setParameter("id", boardId)
                .getSingleResult()).longValue();
    }

    private boolean cardExists(Long id) {
        em.flush();
        em.clear();
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM corkboard_cards WHERE id = :id")
                .setParameter("id", id)
                .getSingleResult()).longValue() == 1L;
    }

    private boolean groupExists(Long id) {
        em.flush();
        em.clear();
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM corkboard_groups WHERE id = :id")
                .setParameter("id", id)
                .getSingleResult()).longValue() == 1L;
    }

    private Long insertBoard(String scopeType, Long scopeId, String name, String editPolicy) {
        String uniqueName = name + " " + System.nanoTime();
        em.createNativeQuery(
                        "INSERT INTO corkboards "
                                + "(scope_type, scope_id, name, background_style, edit_policy, is_default, "
                                + "version, created_at, updated_at) "
                                + "VALUES (:scopeType, :scopeId, :name, 'CORK', :editPolicy, 0, 0, NOW(), NOW())")
                .setParameter("scopeType", scopeType)
                .setParameter("scopeId", scopeId)
                .setParameter("name", uniqueName)
                .setParameter("editPolicy", editPolicy)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM corkboards WHERE name = :name")
                .setParameter("name", uniqueName)
                .getSingleResult()).longValue();
    }

    private Long insertPersonalBoard(Long ownerId, String name) {
        String uniqueName = name + " " + System.nanoTime();
        em.createNativeQuery(
                        "INSERT INTO corkboards "
                                + "(scope_type, owner_id, name, background_style, edit_policy, is_default, "
                                + "version, created_at, updated_at) "
                                + "VALUES ('PERSONAL', :ownerId, :name, 'CORK', 'ADMIN_ONLY', 0, 0, NOW(), NOW())")
                .setParameter("ownerId", ownerId)
                .setParameter("name", uniqueName)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM corkboards WHERE name = :name")
                .setParameter("name", uniqueName)
                .getSingleResult()).longValue();
    }

    private Long insertCard(Long boardId, Long createdBy) {
        em.createNativeQuery(
                        "INSERT INTO corkboard_cards "
                                + "(corkboard_id, card_type, color_label, card_size, position_x, position_y, "
                                + "z_index, is_archived, is_pinned, is_ref_deleted, created_by, "
                                + "created_at, updated_at) "
                                + "VALUES (:boardId, 'MEMO', 'NONE', 'MEDIUM', 0, 0, 0, 0, 0, 0, :createdBy, "
                                + "NOW(), NOW())")
                .setParameter("boardId", boardId)
                .setParameter("createdBy", createdBy)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                        "SELECT id FROM corkboard_cards WHERE corkboard_id = :boardId ORDER BY id DESC LIMIT 1")
                .setParameter("boardId", boardId)
                .getSingleResult()).longValue();
    }

    private Long insertGroup(Long boardId) {
        em.createNativeQuery(
                        "INSERT INTO corkboard_groups (corkboard_id, name, is_collapsed, position_x, position_y, "
                                + "width, height, display_order, created_at, updated_at) "
                                + "VALUES (:boardId, 'CBオラクル セクション', 0, 0, 0, 400, 300, 0, NOW(), NOW())")
                .setParameter("boardId", boardId)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                        "SELECT id FROM corkboard_groups WHERE corkboard_id = :boardId ORDER BY id DESC LIMIT 1")
                .setParameter("boardId", boardId)
                .getSingleResult()).longValue();
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'CBオラクル', 'テスト', 'CBオラクルテスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('cbo-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('cbo-o-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
