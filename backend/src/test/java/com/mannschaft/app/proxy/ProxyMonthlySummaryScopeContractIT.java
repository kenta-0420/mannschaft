package com.mannschaft.app.proxy;

import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治 Wave7 — proxy 代理入力月次サマリ（{@code ProxyMonthlySummaryController}）認可契約テスト。
 *
 * <p>{@code getDownloadUrl} は呼び出し元 userId を引数に持たず、任意の {@code subjectUserId} を
 * 指定して他人の月次サマリ PDF の presigned URL を取得できた。Controller の javadoc は
 * 「ADMIN以上（SecurityConfig で制限）」と書いていたが {@code SecurityConfig} に
 * {@code /api/v1/proxy-input/**} の requestMatcher は存在せず、記述自体が虚偽だった。</p>
 *
 * <p><b>認可モデル</b>（同ドメイン {@code ProxyInputConsentService#generateScanDownloadUrl} に準拠）:
 * 本人 / SYSTEM_ADMIN / 対象住民の未失効同意書が属する組合の ADMIN・DEPUTY_ADMIN のみ 200、
 * それ以外は 403。{@code proxy_input_records} は {@code organizationId} を持たないため、
 * 管理権原の判定軸は<b>同意書 entity 由来の {@code organizationId}</b>（path 値の鵜呑み禁止）。</p>
 *
 * <p>{@code R2StorageService} は外部依存のため {@code @MockitoBean} でモックする
 * （interface 型 {@code StorageService} で置換すると具象型を注入する消費者が壊れるため具象型で置換）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("proxy 代理入力月次サマリ 認可契約テスト（Wave7）")
class ProxyMonthlySummaryScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProxyInputConsentRepository consentRepository;

    @MockitoBean
    private R2StorageService r2StorageService;

    @PersistenceContext
    private EntityManager em;

    private Long orgAId;
    private Long orgBId;

    private Long subjectUserId;   // 代理入力の対象住民（本人）
    private Long proxyUserId;     // 代理者
    private Long adminOrgAId;     // 対象住民の同意書が属する組合の ADMIN（正当）
    private Long adminOrgBId;     // 別組合の ADMIN（越境攻撃者）
    private Long outsiderId;      // 無関係の第三者

    @BeforeEach
    void setUp() {
        orgAId = insertOrganization("PMSAUTHZ 組合A");
        orgBId = insertOrganization("PMSAUTHZ 組合B");

        subjectUserId = insertUser("pmsauthz-subject@example.com");
        proxyUserId = insertUser("pmsauthz-proxy@example.com");
        adminOrgAId = insertUser("pmsauthz-admin-org-a@example.com");
        adminOrgBId = insertUser("pmsauthz-admin-org-b@example.com");
        outsiderId = insertUser("pmsauthz-outsider@example.com");

        // checkAdminOrAbove/isAdminOrAbove（user_roles）と isMember（memberships）は別系統のため
        // ADMIN ユーザーにも memberships 行を張る（Wave 踏襲の既知の地雷）。
        MembershipTestHelper.insertMembership(em, adminOrgAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminOrgAId, "ADMIN", null, orgAId);
        MembershipTestHelper.insertMembership(em, adminOrgBId, ScopeType.ORGANIZATION, orgBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminOrgBId, "ADMIN", null, orgBId);
        // outsiderId / subjectUserId / proxyUserId はどこにも所属させない。

        consentRepository.save(ProxyInputConsentEntity.create(
                subjectUserId, proxyUserId, orgAId,
                ProxyInputConsentEntity.ConsentMethod.PAPER_SIGNED,
                "proxy-consents/pmsauthz.pdf", null, null,
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(30)));

        lenient().when(r2StorageService.generateDownloadUrl(anyString(), any(Duration.class)))
                .thenReturn("https://example.invalid/signed");

        em.flush();
        em.clear();
    }

    @Nested
    @DisplayName("GET /proxy-input/monthly-summaries/{year}/{month}/{subjectUserId}/download-url")
    class GetDownloadUrl {

        @Test
        @DisplayName("C-1特有: 無関係の第三者は他人のsubjectUserIdを指定してもURLを取得できない（403）")
        void 無関係の第三者は403() throws Exception {
            setAuth(outsiderId);
            perform(subjectUserId).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別組合のADMINは403（BOLA・同意書entity由来のorganizationIdで判定）")
        void 別組合のADMINは403() throws Exception {
            setAuth(adminOrgBId);
            perform(subjectUserId).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("代理者本人（proxyUserId）でも対象住民の管理権原が無ければ403")
        void 代理者は403() throws Exception {
            setAuth(proxyUserId);
            perform(subjectUserId).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("C-1特有: 本人は自分のサマリURLを取得できる（200）")
        void 本人は200() throws Exception {
            setAuth(subjectUserId);
            perform(subjectUserId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.downloadUrl").value("https://example.invalid/signed"));
        }

        @Test
        @DisplayName("対象住民の同意書が属する組合のADMINは200（機能非回帰）")
        void 同意書組合のADMINは200() throws Exception {
            setAuth(adminOrgAId);
            perform(subjectUserId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.downloadUrl").value("https://example.invalid/signed"));
        }

        @Test
        @DisplayName("同意書が1件も無い住民のサマリは組合ADMINでも403")
        void 同意書なしは403() throws Exception {
            setAuth(adminOrgAId);
            perform(outsiderId).andExpect(status().isForbidden());
        }

        private org.springframework.test.web.servlet.ResultActions perform(Long targetSubjectUserId)
                throws Exception {
            return mockMvc.perform(get(
                    "/api/v1/proxy-input/monthly-summaries/{year}/{month}/{subjectUserId}/download-url",
                    2026, 4, targetSubjectUserId));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
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
                                + "VALUES (:email, 'PMSAUTHZ', 'テスト', 'PMSAUTHZ テスト', 'ACTIVE', "
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
}
