package com.mannschaft.app.advertising.operational;

import com.mannschaft.app.advertising.AdPlacement;
import com.mannschaft.app.advertising.controller.TeamAdvertiserAdCreativeController;
import com.mannschaft.app.advertising.dto.AdCreativeResponse;
import com.mannschaft.app.advertising.dto.CreateAdCreativeRequest;
import com.mannschaft.app.advertising.dto.UpdateAdCreativeRequest;
import com.mannschaft.app.common.BusinessException;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F09.19.5b チームスコープ クリエイティブ CRUD の帰属検証（IDOR 回帰番人）試練テスト。
 *
 * <p>組織版 {@link AdvertiserAdCreativeIdorIT} のチーム対。{@link TeamAdvertiserAdCreativeController}
 * が scope 解決を ORGANIZATION のまま流用（コピペ越境バグ）していないことを機械的に番人する。</p>
 *
 * <p><b>番人の核心</b>: チーム A の ADMIN が自チーム URL に<b>チーム B のキャンペーン ID</b> を指定した
 * create / list / update / delete は、campaign→advertiser_account→scope(TEAM) の帰属不一致として
 * すべて 403（COMMON_002・存在有無を問わず）で拒否されなければならない。正当な自チームキャンペーンへの
 * create は成功する（常時 403 の弱実装でないことを保証）。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F09.19.5b チームスコープ クリエイティブ帰属検証(IDOR回帰番人) 試練テスト")
class TeamAdvertiserAdCreativeIdorIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private TeamAdvertiserAdCreativeController controller;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long adminAId;
    /** チーム A に帰属する運用型キャンペーン。 */
    private Long campaignAId;
    /** チーム B に帰属する運用型キャンペーン（IDOR 標的）。 */
    private Long campaignBId;
    /** チーム B のキャンペーン配下の既存クリエイティブ（update / delete の標的）。 */
    private Long creativeBId;

    @BeforeEach
    void setUp() {
        insertRole("ADMIN", "管理者", 2);
        Long adminRoleId = roleId("ADMIN");

        adminAId = insertUser("team-idor-admin-a@example.com");
        Long adminBId = insertUser("team-idor-admin-b@example.com");

        teamAId = insertTeam("IDOR チームA");
        teamBId = insertTeam("IDOR チームB");

        insertUserRole(adminAId, adminRoleId, teamAId);
        insertUserRole(adminBId, adminRoleId, teamBId);
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);

        Long accountAId = insertAdvertiserAccount(teamAId, "IDORチームA広告主");
        Long accountBId = insertAdvertiserAccount(teamBId, "IDORチームB広告主");

        campaignAId = insertCampaign(accountAId, "チームAキャンペーン");
        campaignBId = insertCampaign(accountBId, "チームBキャンペーン");
        creativeBId = insertCreative(campaignBId, "チームBの既存クリエイティブ");

        em.flush();
        em.clear();

        // チーム A の ADMIN として認証（自チーム URL は正当・campaignId だけ他チーム）
        setAuthentication(adminAId);
    }

    // ═════════════════════════════════════════════════════════════════════
    // 越境 IDOR 番人（チームB のキャンペーンを指定）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("チームAの ADMIN が自チーム URL にチームBのキャンペーン ID を指定")
    class CrossTenantCampaignId {

        @Test
        @DisplayName("他チームキャンペーンへの create → 403（素通しで作成できない）")
        void 他チームキャンペーンへのcreateは403() {
            assertThatThrownBy(() -> controller.create(teamAId, campaignBId, validCreateRequest()))
                    .as("campaign→advertiser_account→scope(TEAM) の帰属不一致は 403（存在有無を問わず）")
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertForbidden((BusinessException) e));

            Number count = (Number) em.createNativeQuery(
                            "SELECT COUNT(*) FROM ads WHERE campaign_id = :cid")
                    .setParameter("cid", campaignBId)
                    .getSingleResult();
            assertThat(count.longValue())
                    .as("チーム B のキャンペーンにクリエイティブが追加されていないこと")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("他チームキャンペーンの list → 403")
        void 他チームキャンペーンのlistは403() {
            assertThatThrownBy(() -> controller.list(teamAId, campaignBId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertForbidden((BusinessException) e));
        }

        @Test
        @DisplayName("他チームキャンペーンの update → 403")
        void 他チームキャンペーンのupdateは403() {
            UpdateAdCreativeRequest req = new UpdateAdCreativeRequest(
                    "改竄タイトル", null, null, null, null, null, null);

            assertThatThrownBy(() -> controller.update(teamAId, campaignBId, creativeBId, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertForbidden((BusinessException) e));

            em.clear();
            Object title = em.createNativeQuery("SELECT title FROM ads WHERE id = :id")
                    .setParameter("id", creativeBId)
                    .getSingleResult();
            assertThat(title).as("チーム B のクリエイティブが改竄されていないこと")
                    .isEqualTo("チームBの既存クリエイティブ");
        }

        @Test
        @DisplayName("他チームキャンペーンの delete → 403")
        void 他チームキャンペーンのdeleteは403() {
            assertThatThrownBy(() -> controller.delete(teamAId, campaignBId, creativeBId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertForbidden((BusinessException) e));

            em.clear();
            Object status = em.createNativeQuery("SELECT status FROM ads WHERE id = :id")
                    .setParameter("id", creativeBId)
                    .getSingleResult();
            assertThat(status).as("チーム B のクリエイティブが削除（ENDED 化）されていないこと")
                    .isNotEqualTo("ENDED");
        }
    }

    @Nested
    @DisplayName("自チームキャンペーンへの正当な操作は成功する（常時 403 の弱実装でない保証）")
    class OwnTenantSucceeds {

        @Test
        @DisplayName("自チームキャンペーンへの create は成功する")
        void 自チームキャンペーンへのcreateは成功する() {
            AdCreativeResponse res = controller.create(teamAId, campaignAId, validCreateRequest()).getData();
            assertThat(res.id()).as("自チームのキャンペーンにはクリエイティブを作成できる").isNotNull();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void assertForbidden(BusinessException e) {
        assertThat(e.getErrorCode().getCode())
                .as("越境は 403 に解決されるコード（COMMON_002 = 認可拒否）で拒否されること")
                .isEqualTo("COMMON_002");
    }

    private CreateAdCreativeRequest validCreateRequest() {
        return new CreateAdCreativeRequest(
                "越境テスト広告", "https://example.com/img.png", "https://example.com/lp",
                AdPlacement.DASHBOARD_TILE, null, null, null);
    }

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private void insertRole(String name, String displayName, int priority) {
        em.createNativeQuery(
                        "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                + "VALUES (:name, :dn, :priority, 0, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("dn", displayName)
                .setParameter("priority", priority)
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
                                + "VALUES (:email, 'IDOR', 'テスト', 'IDOR テスト', 'ACTIVE', "
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
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private void insertUserRole(Long uid, Long roleId, Long teamId) {
        em.createNativeQuery(
                        "INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                                + "VALUES (:uid, :rid, :tid, NULL, NOW(), NOW())")
                .setParameter("uid", uid)
                .setParameter("rid", roleId)
                .setParameter("tid", teamId)
                .executeUpdate();
    }

    private Long insertAdvertiserAccount(Long teamId, String companyName) {
        em.createNativeQuery(
                        "INSERT INTO advertiser_accounts (scope_type, scope_id, status, company_name, "
                                + "contact_email, billing_method, credit_limit, created_at, updated_at) "
                                + "VALUES ('TEAM', :tid, 'ACTIVE', :cn, 'ads@example.com', "
                                + "'STRIPE', 100000, NOW(), NOW())")
                .setParameter("tid", teamId)
                .setParameter("cn", companyName)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM advertiser_accounts").getSingleResult()).longValue();
    }

    private Long insertCampaign(Long advertiserAccountId, String name) {
        em.createNativeQuery(
                        "INSERT INTO ad_campaigns (advertiser_account_id, name, status, pricing_model, "
                                + "daily_budget, start_date, end_date, created_at, updated_at) "
                                + "VALUES (:aid, :name, 'DRAFT', 'CPM', :budget, "
                                + "DATE_ADD(CURDATE(), INTERVAL 1 DAY), DATE_ADD(CURDATE(), INTERVAL 30 DAY), "
                                + "NOW(), NOW())")
                .setParameter("aid", advertiserAccountId)
                .setParameter("name", name)
                .setParameter("budget", new BigDecimal("1000.00"))
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM ad_campaigns").getSingleResult()).longValue();
    }

    private Long insertCreative(Long campaignId, String title) {
        em.createNativeQuery(
                        "INSERT INTO ads (campaign_id, title, image_url, destination_url, status, "
                                + "created_at, updated_at) "
                                + "VALUES (:cid, :title, 'https://example.com/b.png', 'https://example.com/lp', "
                                + "'ACTIVE', NOW(), NOW())")
                .setParameter("cid", campaignId)
                .setParameter("title", title)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM ads").getSingleResult()).longValue();
    }
}
