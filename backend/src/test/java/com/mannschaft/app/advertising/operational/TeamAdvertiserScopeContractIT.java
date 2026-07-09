package com.mannschaft.app.advertising.operational;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F09.19.5 チームスコープ広告主 API 契約テスト（試練 / red 先行）。
 *
 * <p>正本: {@code docs/features/F09.19_ad_slot_serving.md} §3（チームスコープ完成・scope 化 URL 体系）・
 * §6.1（{@code {scopeBase}=/api/v1/teams/{teamId}} の CRUD/一覧 URL 対）・§16 F09.19.5（AC-5.1〜5.3）。</p>
 *
 * <p>金型: {@code PublicFileLinkContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL）と {@code OperationalAdCampaignCrudIT}（ネイティブ SQL フィクスチャ + 手動 SecurityContext）。
 * Spring Security フィルタは無効化するが、越境 403 は {@code AccessControlService.checkAdminOrAbove}
 * のアプリケーション層例外（{@code COMMON_002} → 403）として発生するためフィルタ無効でも検証できる。</p>
 *
 * <p><b>red 分類（実装不在）</b>: F09.19.5 のチーム対コントローラ
 * （{@code /api/v1/teams/{teamId}/advertiser/**}）と旧 overview の Deprecation ヘッダは未実装のため、
 * 現状は 404 応答 / ヘッダ欠落で red。出陣で以下を実装すると green:</p>
 * <ul>
 *   <li>AC-5.1: {@code POST /api/v1/teams/{teamId}/advertiser/ad-campaigns} → 201
 *       （V144.005/006 適用後・ad_campaigns が advertiser_account_id 経由で scope 化）</li>
 *   <li>AC-5.2: team scope の invoices / credit-limit-requests / report-schedules / performance が
 *       org 版と同一形式で応答。他チームの ADMIN → 403</li>
 *   <li>AC-5.3: 旧 {@code /api/v1/advertiser/overview} が {@code Deprecation: true} ヘッダ付きで従来応答維持</li>
 * </ul>
 *
 * <p>test プロファイルは {@code flyway.enabled=false} + {@code ddl-auto=create} のため、テーブルは
 * Entity から生成される。ad_campaigns の {@code advertiser_account_id} 列は出陣で Entity に追加されるまで
 * 存在しないため、AC-5.1 で作成された行の永続 scope 検証は行わず、201 応答（ルーティング成立）を契約とする。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F09.19.5 チームスコープ広告主 API 契約テスト（試練）")
class TeamAdvertiserScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private Long orgId;
    private Long orgAdminId;
    private Long teamAId;
    private Long teamBId;
    private Long adminAId;
    private Long adminBId;
    private Long sysAdminId;
    private Long rateCardId;

    private static final BigDecimal UNIT_PRICE = new BigDecimal("500.0000");
    private static final BigDecimal MIN_DAILY_BUDGET = new BigDecimal("1000.00");

    @BeforeEach
    void setUp() {
        insertRole("SYSTEM_ADMIN", "システム管理者", 1, true);
        insertRole("ADMIN", "管理者", 2, false);
        Long adminRoleId = roleId("ADMIN");
        Long sysAdminRoleId = roleId("SYSTEM_ADMIN");

        sysAdminId = insertUser("f09195-sysadmin@example.com");
        orgAdminId = insertUser("f09195-org-admin@example.com");
        adminAId = insertUser("f09195-team-a-admin@example.com");
        adminBId = insertUser("f09195-team-b-admin@example.com");

        orgId = insertOrganization("F09195 組織");
        teamAId = insertTeam("F09195 チームA");
        teamBId = insertTeam("F09195 チームB");

        insertUserRole(sysAdminId, sysAdminRoleId, null, null);
        insertUserRole(orgAdminId, adminRoleId, null, orgId);
        insertUserRole(adminAId, adminRoleId, teamAId, null);
        insertUserRole(adminBId, adminRoleId, teamBId, null);

        // scope 化済み広告主アカウント（ORG = overview 用・TEAM A = チーム対 API 用）
        insertAdvertiserAccount("ORGANIZATION", orgId, "組織広告主", "INVOICE");
        insertAdvertiserAccount("TEAM", teamAId, "チームA広告主", "INVOICE");

        rateCardId = insertRateCard("CPM", UNIT_PRICE, MIN_DAILY_BUDGET, -30, null);

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-5.1 チーム対キャンペーン作成
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ac5_1: TEAM の ADMIN が POST /teams/{teamId}/advertiser/ad-campaigns → 201")
    void ac5_1_チームADMINが運用型キャンペーンを作成できる() throws Exception {
        setAuthentication(adminAId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "チーム夏季キャンペーン");
        body.put("pricingModel", "CPM");
        body.put("dailyBudget", new BigDecimal("3000.00"));
        body.put("startDate", LocalDate.now().plusDays(1).toString());
        body.put("endDate", LocalDate.now().plusDays(30).toString());
        body.put("rateCardId", rateCardId);

        mockMvc.perform(post("/api/v1/teams/{teamId}/advertiser/ad-campaigns", teamAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-5.2 チーム対の各リソースが org 版と同一形式で応答・越境 403
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-5.2 チーム対リソースの応答形式パリティと越境 403")
    class Ac5_2_ScopeParity {

        @Test
        @DisplayName("ac5_2: team invoices が org 版と同一の PagedResponse 形式（data + meta）で 200")
        void ac5_2_チーム請求書一覧がPagedResponse形式で応答する() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/advertiser/invoices", teamAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").exists())
                    .andExpect(jsonPath("$.meta").exists());
        }

        @Test
        @DisplayName("ac5_2: team credit-limit-requests が 200 で ApiResponse 形式（data）で応答")
        void ac5_2_チーム増額申請一覧が応答する() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/advertiser/credit-limit-requests", teamAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").exists());
        }

        @Test
        @DisplayName("ac5_2: team report-schedules が 200 で ApiResponse 形式（data）で応答")
        void ac5_2_チームレポートスケジュール一覧が応答する() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/advertiser/report-schedules", teamAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").exists());
        }

        @Test
        @DisplayName("ac5_2: 他チームの ADMIN による team invoices 取得 → 403（越境拒否）")
        void ac5_2_他チームADMINの請求書一覧は403() throws Exception {
            // チーム B の ADMIN がチーム A の URL を叩く
            setAuthentication(adminBId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/advertiser/invoices", teamAId))
                    .andExpect(status().isForbidden());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-5.3 旧 URL の Deprecation ヘッダ
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ac5_3: 旧 /api/v1/advertiser/overview が Deprecation:true ヘッダ付きで従来応答を維持")
    void ac5_3_旧overviewがDeprecationヘッダ付きで応答する() throws Exception {
        setAuthentication(orgAdminId);

        mockMvc.perform(get("/api/v1/advertiser/overview").param("organizationId", orgId.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string("Deprecation", "true"));
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private void insertRole(String name, String displayName, int priority, boolean isSystem) {
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

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'F09195', 'テスト', 'F09195 テスト', 'ACTIVE', "
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

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private void insertUserRole(Long uid, Long roleIdParam, Long teamIdParam, Long orgIdParam) {
        em.createNativeQuery(
                        "INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                                + "VALUES (:uid, :rid, :tid, :oid, NOW(), NOW())")
                .setParameter("uid", uid)
                .setParameter("rid", roleIdParam)
                .setParameter("tid", teamIdParam)
                .setParameter("oid", orgIdParam)
                .executeUpdate();
    }

    private void insertAdvertiserAccount(String scopeType, Long scopeId, String companyName, String billingMethod) {
        em.createNativeQuery(
                        "INSERT INTO advertiser_accounts (scope_type, scope_id, status, company_name, "
                                + "contact_email, billing_method, credit_limit, created_at, updated_at) "
                                + "VALUES (:st, :sid, 'ACTIVE', :cn, 'ads@example.com', :bm, 100000, NOW(), NOW())")
                .setParameter("st", scopeType)
                .setParameter("sid", scopeId)
                .setParameter("cn", companyName)
                .setParameter("bm", billingMethod)
                .executeUpdate();
    }

    private Long insertRateCard(String pricingModel, BigDecimal unitPrice, BigDecimal minDailyBudget,
                                int fromOffsetDays, Integer untilOffsetDays) {
        String until = untilOffsetDays == null
                ? "NULL"
                : "DATE_ADD(CURDATE(), INTERVAL " + untilOffsetDays + " DAY)";
        em.createNativeQuery(
                        "INSERT INTO ad_rate_cards (target_prefecture, target_template, pricing_model, "
                                + "unit_price, min_daily_budget, effective_from, effective_until, "
                                + "created_by, created_at, updated_at) "
                                + "VALUES (NULL, NULL, :pm, :up, :mdb, "
                                + "DATE_ADD(CURDATE(), INTERVAL " + fromOffsetDays + " DAY), " + until + ", "
                                + ":uid, NOW(), NOW())")
                .setParameter("pm", pricingModel)
                .setParameter("up", unitPrice)
                .setParameter("mdb", minDailyBudget)
                .setParameter("uid", sysAdminId)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM ad_rate_cards").getSingleResult()).longValue();
    }
}
