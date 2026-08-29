package com.mannschaft.app.succession.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.succession.entity.DelinquencyEscalationEntity;
import com.mannschaft.app.succession.entity.LegalFilingEntity;
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

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * succession ドメイン（{@code LegalFilingService} / {@code DelinquencyEscalationService}）の
 * 認可契約テスト（認可根治戦役 Wave 2 トランシェ2A #3・試練 / red 先行）。
 *
 * <p>正本: {@code docs/features/F09.15_resident_succession_support.md} §5.7〜§5.13。
 *
 * <p>金型: {@code TeamAdvertiserScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 実 {@code AccessControlService}）。Spring Security フィルタは無効化するが、
 * 越境 403 は {@code AccessControlService.checkAdminOrAbove} のアプリケーション層例外
 * （{@code COMMON_002} → 403）として発生するためフィルタ無効でも検証できる。
 *
 * <p>立退き証拠・弁護士介入情報を扱う最機密ドメインのため、{@code LegalFilingService} /
 * {@code DelinquencyEscalationService} の全メソッドは ADMIN/DEPUTY_ADMIN 以上のみ許可される
 * （手本: 同パッケージ {@code SuccessionCovenantService} / {@code UnsealRequestService}）。
 *
 * <h3>検証観点（4象限 + BOLA）</h3>
 * <ul>
 *   <li>非ADMINメンバー（MEMBER ロールのみ）→ 403（COMMON_002）</li>
 *   <li>別組織の ADMIN（越境） → 403（COMMON_002）</li>
 *   <li>正当な ADMIN → 200/201</li>
 *   <li>BOLA: 正当な ADMIN が path の組織 ID は自組織だが、対象 ID（legalFilingId / escalationId）が
 *       別組織のものである場合 → 404（存在秘匿。{@code (id, organizationId)} 複合キー取得のため
 *       path organizationId を騙っても他組織のエンティティは絶対に返らない）</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("succession ドメイン（法的手続き・滞納エスカレーション）認可契約テスト（試練）")
class SuccessionAuthzContractIT extends AbstractSuccessionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    // 高位ネームスペース（他テストの ID と衝突しないよう 925_xxx 台を使用）
    private static final Long ORG_A = 925_001L;
    private static final Long ORG_B = 925_101L;
    private static final Long ADMIN_A = 925_002L;    // ORG_A の ADMIN（正当操作者）
    private static final Long MEMBER_A = 925_003L;   // ORG_A の MEMBER（非ADMIN・403 期待）
    private static final Long ADMIN_B = 925_102L;    // ORG_B の ADMIN（越境・403 期待）
    private static final Long OUTSIDER = 925_004L;   // どのロールも持たない
    private static final Long DWELLING_A = 925_010L;
    private static final Long RESIDENT_A = 925_011L;
    private static final Long DWELLING_B = 925_110L;
    private static final Long RESIDENT_B = 925_111L;

    private UUID legalFilingA;   // ORG_A 所属
    private UUID legalFilingB;   // ORG_B 所属（BOLA 検証用）
    private UUID escalationA;    // ORG_A 所属
    private UUID escalationB;    // ORG_B 所属（BOLA 検証用）

    @BeforeEach
    void setUp() {
        insertRole("ADMIN", "管理者", 2, false);
        insertRole("MEMBER", "メンバー", 4, false);
        Long adminRoleId = roleId("ADMIN");
        Long memberRoleId = roleId("MEMBER");

        insertUser(ADMIN_A, "s2-3-admin-a@example.com");
        insertUser(MEMBER_A, "s2-3-member-a@example.com");
        insertUser(ADMIN_B, "s2-3-admin-b@example.com");
        insertUser(OUTSIDER, "s2-3-outsider@example.com");

        insertOrganization(ORG_A, "F09.15 認可契約テスト組織A");
        insertOrganization(ORG_B, "F09.15 認可契約テスト組織B");

        insertUserRole(ADMIN_A, adminRoleId, ORG_A);
        insertUserRole(MEMBER_A, memberRoleId, ORG_A);
        insertUserRole(ADMIN_B, adminRoleId, ORG_B);
        MembershipTestHelper.insertMembership(em, ADMIN_A, ScopeType.ORGANIZATION, ORG_A, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, MEMBER_A, ScopeType.ORGANIZATION, ORG_A, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, ADMIN_B, ScopeType.ORGANIZATION, ORG_B, RoleKind.MEMBER);
        // OUTSIDER はどのスコープにも role を持たない

        // legal_filings / delinquency_escalations は cross-domain FK を持たないため
        // dwelling_unit_id / resident_registry_id は実データ不要（ID 参照のみ）
        legalFilingA = persistLegalFiling(ORG_A, DWELLING_A, RESIDENT_A).getId();
        legalFilingB = persistLegalFiling(ORG_B, DWELLING_B, RESIDENT_B).getId();
        escalationA = persistEscalation(ORG_A, DWELLING_A, RESIDENT_A).getId();
        escalationB = persistEscalation(ORG_B, DWELLING_B, RESIDENT_B).getId();

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // LegalFilingController 認可契約
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("LegalFilingController 認可契約")
    class LegalFiling {

        @Test
        @DisplayName("listByOrganization: 正当な ADMIN → 200")
        void listByOrganization_ADMIN_200() throws Exception {
            setAuthentication(ADMIN_A);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/succession/legal-filings", ORG_A))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("listByOrganization: 非ADMINメンバー（MEMBER）→ 403")
        void listByOrganization_非ADMINメンバー_403() throws Exception {
            setAuthentication(MEMBER_A);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/succession/legal-filings", ORG_A))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("listByOrganization: 別組織の ADMIN（越境）→ 403")
        void listByOrganization_別組織ADMIN越境_403() throws Exception {
            setAuthentication(ADMIN_B);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/succession/legal-filings", ORG_A))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("listByOrganization: どのロールも持たないユーザー → 403")
        void listByOrganization_非メンバー_403() throws Exception {
            setAuthentication(OUTSIDER);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/succession/legal-filings", ORG_A))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("createLegalFiling: 正当な ADMIN → 200 で起票できる")
        void createLegalFiling_ADMIN_200() throws Exception {
            setAuthentication(ADMIN_A);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("residentRegistryId", RESIDENT_A);
            body.put("dwellingUnitId", DWELLING_A);
            body.put("filingType", "ABSENTEE_PROPERTY_MANAGER");
            body.put("note", "認可契約テスト");

            mockMvc.perform(post("/api/v1/organizations/{orgId}/succession/legal-filings", ORG_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("createLegalFiling: 非ADMINメンバー → 403（起票不可）")
        void createLegalFiling_非ADMINメンバー_403() throws Exception {
            setAuthentication(MEMBER_A);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("residentRegistryId", RESIDENT_A);
            body.put("dwellingUnitId", DWELLING_A);
            body.put("filingType", "ABSENTEE_PROPERTY_MANAGER");

            mockMvc.perform(post("/api/v1/organizations/{orgId}/succession/legal-filings", ORG_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("getById: 正当な ADMIN が自組織のレコードを取得 → 200")
        void getById_自組織ADMIN_200() throws Exception {
            setAuthentication(ADMIN_A);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/succession/legal-filings/{id}", ORG_A, legalFilingA))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("getById BOLA: 自組織 ADMIN が他組織所有の legalFilingId を指定 → 404（存在秘匿）")
        void getById_BOLA_他組織のIDは404() throws Exception {
            setAuthentication(ADMIN_A);
            // ORG_A の正当な ADMIN だが、legalFilingB は ORG_B 所属 → (id, orgId) 不一致で 404
            mockMvc.perform(get("/api/v1/organizations/{orgId}/succession/legal-filings/{id}", ORG_A, legalFilingB))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("getById: 別組織の ADMIN（越境）→ 403（認可が先に落ちる）")
        void getById_別組織ADMIN越境_403() throws Exception {
            setAuthentication(ADMIN_B);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/succession/legal-filings/{id}", ORG_A, legalFilingA))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("listByResident BOLA: 自組織 ADMIN が residentRegistryId を騙っても他組織分は返らない（0件）")
        void listByResident_BOLA_他組織分は返らない() throws Exception {
            setAuthentication(ADMIN_A);
            // RESIDENT_B は ORG_B の居住者。ORG_A の path で問い合わせても組織フィルタで 0 件（越境漏洩なし）
            mockMvc.perform(get(
                            "/api/v1/organizations/{orgId}/succession/legal-filings/by-resident/{residentRegistryId}",
                            ORG_A, RESIDENT_B))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }

        @Test
        @DisplayName("listByResident: 非ADMINメンバー → 403")
        void listByResident_非ADMINメンバー_403() throws Exception {
            setAuthentication(MEMBER_A);
            mockMvc.perform(get(
                            "/api/v1/organizations/{orgId}/succession/legal-filings/by-resident/{residentRegistryId}",
                            ORG_A, RESIDENT_A))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("evidence-package/download-url: 別組織の ADMIN（越境）→ 403")
        void evidenceDownloadUrl_別組織ADMIN越境_403() throws Exception {
            setAuthentication(ADMIN_B);
            mockMvc.perform(get(
                            "/api/v1/organizations/{orgId}/succession/legal-filings/{id}/evidence-package/download-url",
                            ORG_A, legalFilingA))
                    .andExpect(status().isForbidden());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // DelinquencyEscalationController 認可契約
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("DelinquencyEscalationController 認可契約")
    class DelinquencyEscalation {

        @Test
        @DisplayName("listActive: 正当な ADMIN → 200")
        void listActive_ADMIN_200() throws Exception {
            setAuthentication(ADMIN_A);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/succession/delinquency-escalations", ORG_A))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("listActive: 非ADMINメンバー → 403")
        void listActive_非ADMINメンバー_403() throws Exception {
            setAuthentication(MEMBER_A);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/succession/delinquency-escalations", ORG_A))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("listActive: 別組織の ADMIN（越境）→ 403")
        void listActive_別組織ADMIN越境_403() throws Exception {
            setAuthentication(ADMIN_B);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/succession/delinquency-escalations", ORG_A))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("getById: 正当な ADMIN が自組織のエスカレーションを取得 → 200")
        void getById_自組織ADMIN_200() throws Exception {
            setAuthentication(ADMIN_A);
            mockMvc.perform(get(
                            "/api/v1/organizations/{orgId}/succession/delinquency-escalations/{id}",
                            ORG_A, escalationA))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("getById BOLA: 自組織 ADMIN が他組織所有の escalationId を指定 → 404（存在秘匿）")
        void getById_BOLA_他組織のIDは404() throws Exception {
            setAuthentication(ADMIN_A);
            mockMvc.perform(get(
                            "/api/v1/organizations/{orgId}/succession/delinquency-escalations/{id}",
                            ORG_A, escalationB))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("freeze: 正当な ADMIN → 200 で凍結できる")
        void freeze_ADMIN_200() throws Exception {
            setAuthentication(ADMIN_A);
            Map<String, Object> body = Map.of("reason", "弁護士介入のため");

            mockMvc.perform(post(
                            "/api/v1/organizations/{orgId}/succession/delinquency-escalations/{id}/freeze",
                            ORG_A, escalationA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("freeze: 非ADMINメンバー → 403（凍結不可）")
        void freeze_非ADMINメンバー_403() throws Exception {
            setAuthentication(MEMBER_A);
            Map<String, Object> body = Map.of("reason", "弁護士介入のため");

            mockMvc.perform(post(
                            "/api/v1/organizations/{orgId}/succession/delinquency-escalations/{id}/freeze",
                            ORG_A, escalationA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("freeze: 別組織の ADMIN（越境）→ 403")
        void freeze_別組織ADMIN越境_403() throws Exception {
            setAuthentication(ADMIN_B);
            Map<String, Object> body = Map.of("reason", "越境凍結の試み");

            mockMvc.perform(post(
                            "/api/v1/organizations/{orgId}/succession/delinquency-escalations/{id}/freeze",
                            ORG_A, escalationA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("resolve: 正当な ADMIN → 200 で解決できる")
        void resolve_ADMIN_200() throws Exception {
            setAuthentication(ADMIN_A);
            Map<String, Object> body = Map.of("resolvedReason", "PAID");

            mockMvc.perform(post(
                            "/api/v1/organizations/{orgId}/succession/delinquency-escalations/{id}/resolve",
                            ORG_A, escalationA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("resolve: 非ADMINメンバー → 403（解決不可）")
        void resolve_非ADMINメンバー_403() throws Exception {
            setAuthentication(MEMBER_A);
            Map<String, Object> body = Map.of("resolvedReason", "PAID");

            mockMvc.perform(post(
                            "/api/v1/organizations/{orgId}/succession/delinquency-escalations/{id}/resolve",
                            ORG_A, escalationA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("resolve BOLA: 自組織 ADMIN が他組織所有の escalationId を指定 → 404（存在秘匿）")
        void resolve_BOLA_他組織のIDは404() throws Exception {
            setAuthentication(ADMIN_A);
            Map<String, Object> body = Map.of("resolvedReason", "PAID");

            mockMvc.perform(post(
                            "/api/v1/organizations/{orgId}/succession/delinquency-escalations/{id}/resolve",
                            ORG_A, escalationB)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isNotFound());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ロットDステータス契約: 状態競合系（ALREADY_RESOLVED/FROZEN/EVIDENCE_NOT_READY）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ロットDステータス契約（SUCCESSION_017/018/022）")
    class LotDStatusContract {

        @Test
        @DisplayName("resolve: 既に解決済みのエスカレーションを再解決 → 409（ESCALATION_ALREADY_RESOLVED）")
        void resolve_解決済みの再解決は409() throws Exception {
            setAuthentication(ADMIN_A);
            Map<String, Object> body = Map.of("resolvedReason", "PAID");

            // 1回目: 正常に解決
            mockMvc.perform(post(
                            "/api/v1/organizations/{orgId}/succession/delinquency-escalations/{id}/resolve",
                            ORG_A, escalationA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk());

            // 2回目: 既に解決済み → 409
            mockMvc.perform(post(
                            "/api/v1/organizations/{orgId}/succession/delinquency-escalations/{id}/resolve",
                            ORG_A, escalationA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isConflict());
        }

        /**
         * resolve() は凍結中でも解決を許容する意図的な仕様
         * （{@code DelinquencyEscalationService#resolve} のコメント「解決済みチェック
         * （凍結中でも解決は許容する）」参照。{@code fetchEntity} のみを呼び、凍結チェックを
         * 行う {@code getValidEscalation} を経由しない）。
         * ESCALATION_FROZEN が実際に投げられるのは freeze() の再実行（二重凍結）のみ
         * （{@code getValidEscalation} 経由）。ロットDでは当初 resolve に対して誤った
         * 前提のテストを書いていたため、実コードに合わせて対象操作を freeze の再実行に是正する。
         */
        @Test
        @DisplayName("freeze: 既に凍結中のエスカレーションを再凍結しようとする → 409（ESCALATION_FROZEN）")
        void freeze_凍結中の再凍結は409() throws Exception {
            setAuthentication(ADMIN_A);
            Map<String, Object> freezeBody = Map.of("reason", "弁護士介入のため");
            mockMvc.perform(post(
                            "/api/v1/organizations/{orgId}/succession/delinquency-escalations/{id}/freeze",
                            ORG_A, escalationA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(freezeBody)))
                    .andExpect(status().isOk());

            mockMvc.perform(post(
                            "/api/v1/organizations/{orgId}/succession/delinquency-escalations/{id}/freeze",
                            ORG_A, escalationA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(freezeBody)))
                    .andExpect(status().isConflict());
        }

        /**
         * 凍結中でも resolve() は成功することを固定する（上の freeze 再実行テストと対になる仕様確認）。
         */
        @Test
        @DisplayName("resolve: 凍結中のエスカレーションでも解決は許容され200（意図的な製品仕様の固定）")
        void resolve_凍結中でも200() throws Exception {
            setAuthentication(ADMIN_A);
            Map<String, Object> freezeBody = Map.of("reason", "弁護士介入のため");
            mockMvc.perform(post(
                            "/api/v1/organizations/{orgId}/succession/delinquency-escalations/{id}/freeze",
                            ORG_A, escalationA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(freezeBody)))
                    .andExpect(status().isOk());

            Map<String, Object> resolveBody = Map.of("resolvedReason", "PAID");
            mockMvc.perform(post(
                            "/api/v1/organizations/{orgId}/succession/delinquency-escalations/{id}/resolve",
                            ORG_A, escalationA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(resolveBody)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("evidence-package/download-url: 証拠パッケージ未生成のダウンロードは409（EVIDENCE_NOT_READY）")
        void evidenceDownloadUrl_未生成は409() throws Exception {
            setAuthentication(ADMIN_A);
            mockMvc.perform(get(
                            "/api/v1/organizations/{orgId}/succession/legal-filings/{id}/evidence-package/download-url",
                            ORG_A, legalFilingA))
                    .andExpect(status().isConflict());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private void insertRole(String name, String displayName, int priority, boolean isSystem) {
        // 冪等化: roles はグローバル参照テーブルのため、既存なら再利用し二重INSERTしない
        // （同一 name の重複INSERTは roles の UNIQUE 制約違反になる。CI shard 再編成で
        // 同一 JVM 内の同居テストが変わり得るため、盲目的 INSERT は禁止）。
        Number existingRoleCount = (Number) em.createNativeQuery("SELECT COUNT(*) FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult();
        if (existingRoleCount.longValue() > 0) {
            return;
        }
        em.createNativeQuery(
                        "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                + "VALUES (:name, :dn, :priority, :sys, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("dn", displayName)
                .setParameter("priority", priority)
                .setParameter("sys", isSystem ? 1 : 0)
                .executeUpdate();
    }

    private Long roleId(String name) {
        return ((Number) em.createNativeQuery("SELECT id FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private void insertUser(Long userId, String email) {
        em.createNativeQuery(
                        "INSERT INTO users (id, "
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:userId, :email, 'S2-3', 'テスト', 'S2-3 テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("userId", userId)
                .setParameter("email", email)
                .executeUpdate();
    }

    private void insertOrganization(Long orgId, String name) {
        em.createNativeQuery(
                        "INSERT INTO organizations (id, name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:orgId, :name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("orgId", orgId)
                .setParameter("name", name)
                .executeUpdate();
    }

    private void insertUserRole(Long userId, Long roleIdParam, Long organizationId) {
        em.createNativeQuery(
                        "INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                                + "VALUES (:uid, :rid, NULL, :oid, NOW(), NOW())")
                .setParameter("uid", userId)
                .setParameter("rid", roleIdParam)
                .setParameter("oid", organizationId)
                .executeUpdate();
    }

    private LegalFilingEntity persistLegalFiling(Long organizationId, Long dwellingUnitId, Long residentRegistryId) {
        LegalFilingEntity entity = LegalFilingEntity.builder()
                .organizationId(organizationId)
                .dwellingUnitId(dwellingUnitId)
                .residentRegistryId(residentRegistryId)
                .filingType("ABSENTEE_PROPERTY_MANAGER")
                .note("認可契約テスト用フィクスチャ")
                .build();
        em.persist(entity);
        return entity;
    }

    private DelinquencyEscalationEntity persistEscalation(Long organizationId, Long dwellingUnitId, Long residentRegistryId) {
        DelinquencyEscalationEntity entity = DelinquencyEscalationEntity.builder()
                .organizationId(organizationId)
                .dwellingUnitId(dwellingUnitId)
                .residentRegistryId(residentRegistryId)
                .delinquencyStartedAt(LocalDate.of(2026, 1, 1))
                .currentStage("STAGE_2_EMERGENCY_CONTACT")
                .build();
        em.persist(entity);
        return entity;
    }
}
