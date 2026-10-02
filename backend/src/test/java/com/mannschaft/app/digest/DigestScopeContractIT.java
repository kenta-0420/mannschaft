package com.mannschaft.app.digest;

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
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave2 トランシェ2C: digest ドメイン API 契約テスト（試練）。
 *
 * <p>正本: {@code .claude/campaigns/2026-07-10-authz-idor-audit.md}（digest 節。
 * 「Config/Generation 全体が認可なし」）・{@code AccessControlService}
 * （{@code checkMembership}/{@code checkAdminOrAbove}）。
 * 金型: {@code ParkingScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL。
 * 越境 403/404 はアプリ層例外として認可フィルタ無効でも検証できる）。</p>

 * <p>担当スコープ（他は対象外）:</p>
 * <ul>
 *   <li>DigestGenerationService generate/regenerate/publish/discard/edit（変更系）:
 *       checkAdminOrAbove。ID 直指定エンドポイントは entity 由来 scope で検証（BOLA対策）</li>
 *   <li>DigestGenerationService list/getDetail（閲覧系）: checkMembership</li>
 *   <li>DigestConfigService getConfig（閲覧系）=checkMembership、
 *       createOrUpdateConfig/deleteConfig（変更系）=checkAdminOrAbove</li>
 *   <li>DIGEST_011/DIGEST_014 の 404 マッピング（GlobalExceptionHandler・存在秘匿）</li>
 *   <li>DigestAdminController(/api/v1/system-admin/**) は SecurityConfig の
 *       hasRole("SYSTEM_ADMIN") 包括ルールが既に適用済みのため本 IT の対象外</li>
 * </ul>
 *
 * <p>ADMIN 役の被験者は {@code checkMembership}（memberships 表）と
 * {@code checkAdminOrAbove}（user_roles 表）の両方を満たすよう二重に seed する
 * （認可根治戦役 Wave0+1 で確立した既知の地雷）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("digest ドメイン API 契約テスト（認可根治 Wave2 トランシェ2C）")
class DigestScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long adminAId;
    private Long adminBId;
    private Long memberAId;
    private Long outsiderId;

    @BeforeEach
    void setUp() {
        insertRoleIfAbsent("ADMIN", "管理者", 2);

        teamAId = insertTeam("DG認可契約チームA");
        teamBId = insertTeam("DG認可契約チームB");

        adminAId = insertUser("dg-authz-admin-a@example.com");
        adminBId = insertUser("dg-authz-admin-b@example.com");
        memberAId = insertUser("dg-authz-member-a@example.com");
        outsiderId = insertUser("dg-authz-outsider@example.com");

        // ADMIN 役は checkMembership(memberships) と checkAdminOrAbove(user_roles) の両方を満たす必要がある
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);

        // memberA はチームAの一般メンバー（ADMIN権限なし）
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        // outsiderId はどちらのチームにも一切所属しない

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // ダイジェスト生成・一覧・詳細（DigestGenerationService）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ダイジェスト生成(generate)")
    class Generate {

        @Test
        @DisplayName("非ADMINメンバーの手動生成は403（変更系はcheckAdminOrAbove）")
        void 非ADMINメンバーの手動生成は403() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(post("/api/v1/timeline-digest/generate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(generateBody(teamAId))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINが request の scopeId にチームAを指定して生成すると403（越境拒否）")
        void 他チームADMINの越境生成は403() throws Exception {
            setAuthentication(adminBId); // チームBのADMINがチームAのダイジェスト生成を試みる

            mockMvc.perform(post("/api/v1/timeline-digest/generate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(generateBody(teamAId))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("非メンバーの手動生成は403")
        void 非メンバーの手動生成は403() throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(post("/api/v1/timeline-digest/generate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(generateBody(teamAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINのTEMPLATE手動生成は成功（2xx）")
        void 正当ADMINの手動生成は成功() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(post("/api/v1/timeline-digest/generate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(generateBody(teamAId))))
                    .andExpect(status().is2xxSuccessful())
                    .andExpect(jsonPath("$.data.id").exists());
        }
    }

    @Nested
    @DisplayName("ダイジェスト一覧(list)・詳細(getDetail)")
    class ListAndDetail {

        @Test
        @DisplayName("非メンバーの一覧取得は403（閲覧系はcheckMembership）")
        void 非メンバーの一覧は403() throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(get("/api/v1/timeline-digest")
                            .param("scopeType", "TEAM")
                            .param("scopeId", teamAId.toString()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINの一覧取得は403（越境拒否）")
        void 他チームADMINの一覧は403() throws Exception {
            setAuthentication(adminBId);

            mockMvc.perform(get("/api/v1/timeline-digest")
                            .param("scopeType", "TEAM")
                            .param("scopeId", teamAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("一般メンバーの一覧取得は200")
        void 一般メンバーの一覧は200() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(get("/api/v1/timeline-digest")
                            .param("scopeType", "TEAM")
                            .param("scopeId", teamAId.toString()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非メンバーの詳細取得は403")
        void 非メンバーの詳細は403() throws Exception {
            Long digestId = insertDigest(teamAId, "GENERATED");

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/timeline-digest/{id}", digestId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINがチームAのダイジェストIDを直指定して閲覧すると403（BOLA: entity由来scopeで検証）")
        void 他チームADMINの越境詳細は403() throws Exception {
            Long digestId = insertDigest(teamAId, "GENERATED");

            setAuthentication(adminBId);
            mockMvc.perform(get("/api/v1/timeline-digest/{id}", digestId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーの詳細取得は200")
        void 正当メンバーの詳細は200() throws Exception {
            Long digestId = insertDigest(teamAId, "GENERATED");

            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/timeline-digest/{id}", digestId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(digestId));
        }

        @Test
        @DisplayName("不在IDの詳細取得は404（DIGEST_011の404マッピング・存在秘匿）")
        void 不在IDの詳細は404() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(get("/api/v1/timeline-digest/{id}", 999_999_999L))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("DIGEST_011"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ダイジェスト変更系（publish / discard / regenerate / edit）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ダイジェスト変更系(publish/discard/regenerate/edit)")
    class Mutations {

        @Test
        @DisplayName("非ADMINメンバーの公開は403")
        void 非ADMINメンバーの公開は403() throws Exception {
            Long digestId = insertDigest(teamAId, "GENERATED");

            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/timeline-digest/{id}/publish", digestId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINがチームAのダイジェストを公開しようとすると403（BOLA）")
        void 他チームADMINの越境公開は403() throws Exception {
            Long digestId = insertDigest(teamAId, "GENERATED");

            setAuthentication(adminBId);
            mockMvc.perform(post("/api/v1/timeline-digest/{id}/publish", digestId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーの破棄は403")
        void 非ADMINメンバーの破棄は403() throws Exception {
            Long digestId = insertDigest(teamAId, "GENERATED");

            setAuthentication(memberAId);
            mockMvc.perform(delete("/api/v1/timeline-digest/{id}", digestId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの破棄は200")
        void 正当ADMINの破棄は200() throws Exception {
            Long digestId = insertDigest(teamAId, "GENERATED");

            setAuthentication(adminAId);
            mockMvc.perform(delete("/api/v1/timeline-digest/{id}", digestId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("DISCARDED"));
        }

        @Test
        @DisplayName("非ADMINメンバーの再生成は403")
        void 非ADMINメンバーの再生成は403() throws Exception {
            Long digestId = insertDigest(teamAId, "GENERATED");

            setAuthentication(memberAId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("digestStyle", "TEMPLATE");

            mockMvc.perform(post("/api/v1/timeline-digest/{id}/regenerate", digestId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINのTEMPLATE再生成は成功（2xx）")
        void 正当ADMINの再生成は成功() throws Exception {
            Long digestId = insertDigest(teamAId, "GENERATED");

            setAuthentication(adminAId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("digestStyle", "TEMPLATE");

            mockMvc.perform(post("/api/v1/timeline-digest/{id}/regenerate", digestId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().is2xxSuccessful())
                    .andExpect(jsonPath("$.data.id").exists());
        }

        @Test
        @DisplayName("他チームADMINがチームAのダイジェストを編集しようとすると403（BOLA）")
        void 他チームADMINの越境編集は403() throws Exception {
            Long digestId = insertDigest(teamAId, "GENERATED");

            setAuthentication(adminBId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("generatedTitle", "乗っ取りタイトル");

            mockMvc.perform(patch("/api/v1/timeline-digest/{id}", digestId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの編集は200")
        void 正当ADMINの編集は200() throws Exception {
            Long digestId = insertDigest(teamAId, "GENERATED");

            setAuthentication(adminAId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("generatedTitle", "修正後タイトル");

            mockMvc.perform(patch("/api/v1/timeline-digest/{id}", digestId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.generatedTitle").value("修正後タイトル"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 自動生成設定（DigestConfigService）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("自動生成設定(config)")
    class Config {

        @Test
        @DisplayName("非メンバーの設定取得は403（閲覧系はcheckMembership・設定不在の404より優先）")
        void 非メンバーの設定取得は403() throws Exception {
            insertConfig(teamAId, adminAId);

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/timeline-digest/config")
                            .param("scopeType", "TEAM")
                            .param("scopeId", teamAId.toString()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINの設定取得は403（越境拒否）")
        void 他チームADMINの設定取得は403() throws Exception {
            insertConfig(teamAId, adminAId);

            setAuthentication(adminBId);
            mockMvc.perform(get("/api/v1/timeline-digest/config")
                            .param("scopeType", "TEAM")
                            .param("scopeId", teamAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("一般メンバーの設定取得は200")
        void 一般メンバーの設定取得は200() throws Exception {
            insertConfig(teamAId, adminAId);

            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/timeline-digest/config")
                            .param("scopeType", "TEAM")
                            .param("scopeId", teamAId.toString()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("設定不在は404（DIGEST_014の404マッピング）")
        void 設定不在は404() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(get("/api/v1/timeline-digest/config")
                            .param("scopeType", "TEAM")
                            .param("scopeId", teamAId.toString()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("DIGEST_014"));
        }

        @Test
        @DisplayName("非ADMINメンバーの設定作成は403（変更系はcheckAdminOrAbove）")
        void 非ADMINメンバーの設定作成は403() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(put("/api/v1/timeline-digest/config")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(configBody(teamAId))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINが request の scopeId にチームAを指定して設定作成すると403（越境拒否）")
        void 他チームADMINの越境設定作成は403() throws Exception {
            setAuthentication(adminBId);

            mockMvc.perform(put("/api/v1/timeline-digest/config")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(configBody(teamAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINの設定新規作成は201")
        void 正当ADMINの設定新規作成は201() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(put("/api/v1/timeline-digest/config")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(configBody(teamAId))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data").exists());
        }

        @Test
        @DisplayName("非ADMINメンバーの設定削除は403")
        void 非ADMINメンバーの設定削除は403() throws Exception {
            insertConfig(teamAId, adminAId);

            setAuthentication(memberAId);
            mockMvc.perform(delete("/api/v1/timeline-digest/config")
                            .param("scopeType", "TEAM")
                            .param("scopeId", teamAId.toString()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの設定削除は204")
        void 正当ADMINの設定削除は204() throws Exception {
            insertConfig(teamAId, adminAId);

            setAuthentication(adminAId);
            mockMvc.perform(delete("/api/v1/timeline-digest/config")
                            .param("scopeType", "TEAM")
                            .param("scopeId", teamAId.toString()))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /** 生成リクエスト body（TEMPLATE スタイル・過去7日間）。 */
    private Map<String, Object> generateBody(Long scopeId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scopeType", "TEAM");
        body.put("scopeId", scopeId);
        body.put("periodStart", "2026-07-01T00:00:00");
        body.put("periodEnd", "2026-07-07T23:59:59");
        body.put("digestStyle", "TEMPLATE");
        return body;
    }

    /** 設定作成リクエスト body（MANUAL・必須項目のみ。省略可フィールドは明示 null を避けて省略）。 */
    private Map<String, Object> configBody(Long scopeId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scopeType", "TEAM");
        body.put("scopeId", scopeId);
        body.put("scheduleType", "MANUAL");
        body.put("digestStyle", "SUMMARY");
        body.put("timezone", "Asia/Tokyo");
        return body;
    }

    /** roles を name で引く idempotent seed（グローバル参照テーブルのため deleteAll しない）。 */
    private void insertRoleIfAbsent(String name, String displayName, int priority) {
        Number count = (Number) em.createNativeQuery("SELECT COUNT(*) FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult();
        if (count.longValue() > 0) {
            return;
        }
        em.createNativeQuery(
                        "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                + "VALUES (:name, :dn, :priority, 0, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("dn", displayName)
                .setParameter("priority", priority)
                .executeUpdate();
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
                                + "VALUES (:email, 'DG契約', 'テスト', 'DG契約テスト', 'ACTIVE', "
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
                                + "CONCAT('dg-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    /**
     * timeline_digests へ TEAM スコープのダイジェストを 1 行 INSERT する。
     * NOT NULL 列（scope_type/scope_id/period_start/period_end/digest_style/status/created_at）を
     * すべて明示する（@Builder.Default は DDL デフォルトを生成しないため省略不可）。
     */
    private Long insertDigest(Long teamId, String status) {
        em.createNativeQuery(
                        "INSERT INTO timeline_digests (scope_type, scope_id, period_start, period_end, "
                                + "digest_style, status, generated_title, generated_body, generated_excerpt, "
                                + "triggered_by, created_at) "
                                + "VALUES ('TEAM', :sid, '2026-06-01 00:00:00', '2026-06-07 23:59:59', "
                                + "'TEMPLATE', :status, 'テストダイジェスト', '本文', '抜粋', "
                                + ":triggeredBy, NOW())")
                .setParameter("sid", teamId)
                .setParameter("status", status)
                .setParameter("triggeredBy", adminAId)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM timeline_digests").getSingleResult()).longValue();
    }

    /**
     * timeline_digest_configs へ TEAM スコープの自動生成設定を 1 行 INSERT する。
     * entity（TimelineDigestConfigEntity）の NOT NULL 列をすべて明示する。
     */
    private Long insertConfig(Long teamId, Long createdBy) {
        em.createNativeQuery(
                        "INSERT INTO timeline_digest_configs (scope_type, scope_id, schedule_type, "
                                + "digest_style, auto_publish, include_reactions, include_polls, "
                                + "include_diff_from_previous, min_posts_threshold, max_posts_per_digest, "
                                + "timezone, content_max_chars, language, is_enabled, created_by, "
                                + "created_at, updated_at) "
                                + "VALUES ('TEAM', :sid, 'MANUAL', "
                                + "'SUMMARY', 0, 1, 1, "
                                + "0, 3, 100, "
                                + "'Asia/Tokyo', 500, 'ja', 1, :createdBy, "
                                + "NOW(), NOW())")
                .setParameter("sid", teamId)
                .setParameter("createdBy", createdBy)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM timeline_digest_configs").getSingleResult()).longValue();
    }
}
