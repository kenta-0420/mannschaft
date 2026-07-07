package com.mannschaft.app.advertising.operational;

import com.mannschaft.app.advertising.AdPlacement;
import com.mannschaft.app.advertising.controller.AdvertiserAdCreativeController;
import com.mannschaft.app.advertising.dto.AdCreativeResponse;
import com.mannschaft.app.advertising.dto.CreateAdCreativeRequest;
import com.mannschaft.app.advertising.dto.UpdateAdCreativeRequest;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F09.19.1 クリエイティブ CRUD の帰属検証（AC-1.7 IDOR 回帰番人）+ 入稿拡張（AC-1.8）試練テスト。
 *
 * <p><b>AC-1.7（既知 IDOR の閉塞）</b>: 既存 {@link AdvertiserAdCreativeController} は
 * {@code verifyOrganizationAccess(organizationId)} のみで、パス上の {@code campaignId} が当該 scope の
 * 広告主に属するかを未検証のまま Service へ渡している（origin/main 裏取り済み・正本 §6.5）。
 * 組織 A の ADMIN が自組織 URL に<b>組織 B のキャンペーン ID</b> を指定した
 * create / list / update / delete はいずれも 403 で拒否されなければならない。
 * <b>現行実装は素通しのため本テストは必ず red になる。</b></p>
 *
 * <p><b>AC-1.8（入稿拡張）</b>: placement 必須（欠落 400）・width / height / altText が保存され
 * 応答に含まれること（V144.001 / §5.2）。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F09.19.1 クリエイティブ帰属検証(IDOR回帰番人)＋入稿拡張 試練テスト")
class AdvertiserAdCreativeIdorIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private AdvertiserAdCreativeController controller;

    @PersistenceContext
    private EntityManager em;

    private Long orgAId;
    private Long orgBId;
    private Long adminAId;
    /** 組織 A に帰属する運用型キャンペーン。 */
    private Long campaignAId;
    /** 組織 B に帰属する運用型キャンペーン（IDOR 標的）。 */
    private Long campaignBId;
    /** 組織 B のキャンペーン配下の既存クリエイティブ（update / delete の標的）。 */
    private Long creativeBId;

    @BeforeEach
    void setUp() {
        insertRole("ADMIN", "管理者", 2);
        Long adminRoleId = roleId("ADMIN");

        adminAId = insertUser("idor-admin-a@example.com");
        Long adminBId = insertUser("idor-admin-b@example.com");

        orgAId = insertOrganization("IDOR 組織A");
        orgBId = insertOrganization("IDOR 組織B");

        insertUserRole(adminAId, adminRoleId, orgAId);
        insertUserRole(adminBId, adminRoleId, orgBId);

        insertAdvertiserAccount(orgAId, "IDOR組織A広告主");
        insertAdvertiserAccount(orgBId, "IDOR組織B広告主");

        campaignAId = insertCampaign(orgAId, "組織Aキャンペーン");
        campaignBId = insertCampaign(orgBId, "組織Bキャンペーン");
        creativeBId = insertCreative(campaignBId, "組織Bの既存クリエイティブ");

        em.flush();
        em.clear();

        // 組織 A の ADMIN として認証（自組織 URL は正当・campaignId だけ他組織）
        setAuthentication(adminAId);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-1.7 IDOR 回帰番人
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-1.7 組織Aの ADMIN が自組織 URL に組織Bのキャンペーン ID を指定")
    class Ac1_7_CrossTenantCampaignId {

        @Test
        @DisplayName("ac1_7: クリエイティブ create → 403（現行は素通しで作成できてしまう）")
        void ac1_7_他組織キャンペーンへのcreateは403() {
            assertThatThrownBy(() -> controller.create(orgAId, campaignBId, validCreateRequest()))
                    .as("campaign→advertiser_account→scope の帰属不一致は 403（存在有無を問わず）")
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertForbidden((BusinessException) e));

            // 素通しで組織 B のキャンペーンにクリエイティブが増えていないこと
            Number count = (Number) em.createNativeQuery(
                            "SELECT COUNT(*) FROM ads WHERE campaign_id = :cid")
                    .setParameter("cid", campaignBId)
                    .getSingleResult();
            assertThat(count.longValue())
                    .as("組織 B のキャンペーンにクリエイティブが追加されていないこと")
                    .isEqualTo(1L); // 既存 creativeB のみ
        }

        @Test
        @DisplayName("ac1_7: クリエイティブ list → 403（現行は組織Bのクリエイティブが露出する）")
        void ac1_7_他組織キャンペーンのlistは403() {
            assertThatThrownBy(() -> controller.list(orgAId, campaignBId))
                    .as("越境 list は 403 で情報を返さない")
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertForbidden((BusinessException) e));
        }

        @Test
        @DisplayName("ac1_7: クリエイティブ update → 403（現行は他組織のクリエイティブを書き換えられる）")
        void ac1_7_他組織キャンペーンのupdateは403() {
            UpdateAdCreativeRequest req = new UpdateAdCreativeRequest(
                    "改竄タイトル", null, null, null, null, null, null);

            assertThatThrownBy(() -> controller.update(orgAId, campaignBId, creativeBId, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertForbidden((BusinessException) e));

            // 改竄されていないこと
            em.clear();
            Object title = em.createNativeQuery("SELECT title FROM ads WHERE id = :id")
                    .setParameter("id", creativeBId)
                    .getSingleResult();
            assertThat(title).as("組織 B のクリエイティブが改竄されていないこと")
                    .isEqualTo("組織Bの既存クリエイティブ");
        }

        @Test
        @DisplayName("ac1_7: クリエイティブ delete → 403（現行は他組織のクリエイティブを削除できる）")
        void ac1_7_他組織キャンペーンのdeleteは403() {
            assertThatThrownBy(() -> controller.delete(orgAId, campaignBId, creativeBId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertForbidden((BusinessException) e));

            // 論理削除（status=ENDED）されていないこと
            em.clear();
            Object status = em.createNativeQuery("SELECT status FROM ads WHERE id = :id")
                    .setParameter("id", creativeBId)
                    .getSingleResult();
            assertThat(status).as("組織 B のクリエイティブが削除（ENDED 化）されていないこと")
                    .isNotEqualTo("ENDED");
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-1.8 入稿拡張（placement 必須・width/height/altText 保存＋応答）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-1.8 クリエイティブ入稿拡張")
    class Ac1_8_CreativeIntake {

        @Test
        @DisplayName("ac1_8: placement 欠落は Bean Validation 違反（欠落 400 の契約）")
        void ac1_8_placement欠落はバリデーション違反() {
            try (var factory = Validation.buildDefaultValidatorFactory()) {
                Validator validator = factory.getValidator();
                CreateAdCreativeRequest req = new CreateAdCreativeRequest(
                        "placement なし広告", "https://example.com/img.png", "https://example.com/lp",
                        null, null, null, null);

                Set<ConstraintViolation<CreateAdCreativeRequest>> violations = validator.validate(req);

                assertThat(violations)
                        .as("placement は必須（F09.19 §5.2 — @Valid 経由で 400 になる契約）")
                        .anySatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("placement"));
            }
        }

        @Test
        @DisplayName("ac1_8: placement/width/height/altText が保存され応答に含まれる")
        void ac1_8_placementとバナー属性が保存され応答に含まれる() {
            CreateAdCreativeRequest req = new CreateAdCreativeRequest(
                    "バナー広告", "https://example.com/banner.png", "https://example.com/lp",
                    AdPlacement.DASHBOARD_TILE, 300, 250, "夏季セールのバナー");

            AdCreativeResponse res = controller.create(orgAId, campaignAId, req).getData();

            assertThat(res.placement()).as("応答に placement が含まれる").isEqualTo(AdPlacement.DASHBOARD_TILE);
            assertThat(res.width()).as("応答に width が含まれる").isEqualTo(300);
            assertThat(res.height()).as("応答に height が含まれる").isEqualTo(250);
            assertThat(res.altText()).as("応答に altText が含まれる").isEqualTo("夏季セールのバナー");

            // DB へ永続化されていること（実列名で直接確認）
            em.flush();
            em.clear();
            Object[] row = (Object[]) em.createNativeQuery(
                            "SELECT placement, width, height, alt_text FROM ads WHERE id = :id")
                    .setParameter("id", res.id())
                    .getSingleResult();
            assertThat(row[0]).as("ads.placement が保存される").isEqualTo("DASHBOARD_TILE");
            assertThat(((Number) row[1]).intValue()).isEqualTo(300);
            assertThat(((Number) row[2]).intValue()).isEqualTo(250);
            assertThat(row[3]).isEqualTo("夏季セールのバナー");
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

    private void insertUserRole(Long uid, Long roleId, Long orgId) {
        em.createNativeQuery(
                        "INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                                + "VALUES (:uid, :rid, NULL, :oid, NOW(), NOW())")
                .setParameter("uid", uid)
                .setParameter("rid", roleId)
                .setParameter("oid", orgId)
                .executeUpdate();
    }

    private void insertAdvertiserAccount(Long orgId, String companyName) {
        em.createNativeQuery(
                        "INSERT INTO advertiser_accounts (scope_type, scope_id, status, company_name, "
                                + "contact_email, billing_method, credit_limit, created_at, updated_at) "
                                + "VALUES ('ORGANIZATION', :oid, 'ACTIVE', :cn, 'ads@example.com', "
                                + "'STRIPE', 100000, NOW(), NOW())")
                .setParameter("oid", orgId)
                .setParameter("cn", companyName)
                .executeUpdate();
    }

    private Long insertCampaign(Long orgId, String name) {
        em.createNativeQuery(
                        "INSERT INTO ad_campaigns (advertiser_organization_id, name, status, pricing_model, "
                                + "daily_budget, start_date, end_date, created_at, updated_at) "
                                + "VALUES (:oid, :name, 'DRAFT', 'CPM', :budget, "
                                + "DATE_ADD(CURDATE(), INTERVAL 1 DAY), DATE_ADD(CURDATE(), INTERVAL 30 DAY), "
                                + "NOW(), NOW())")
                .setParameter("oid", orgId)
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
