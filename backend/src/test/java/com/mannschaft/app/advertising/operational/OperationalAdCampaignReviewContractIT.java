package com.mannschaft.app.advertising.operational;

import com.mannschaft.app.advertising.AdvertisingErrorCode;
import com.mannschaft.app.advertising.PricingModel;
import com.mannschaft.app.advertising.controller.AdvertiserDashboardController;
import com.mannschaft.app.advertising.controller.OrganizationOperationalAdCampaignController;
import com.mannschaft.app.advertising.controller.SystemAdminOperationalAdCampaignController;
import com.mannschaft.app.advertising.dto.AdCreativeResponse;
import com.mannschaft.app.advertising.dto.CreateOperationalCampaignRequest;
import com.mannschaft.app.advertising.dto.OperationalCampaignResponse;
import com.mannschaft.app.advertising.dto.OperationalCampaignReviewDetailResponse;
import com.mannschaft.app.advertising.dto.PublicRateCardResponse;
import com.mannschaft.app.advertising.entity.AdCampaignEntity.CampaignStatus;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F09.19.1b 契約補完テスト（試練 / red 先行）。
 *
 * <p>.4b（運用型キャンペーン管理画面 FE）が「.1 の契約ギャップで実装不能」と報告した 2 点を検証する:</p>
 * <ul>
 *   <li><b>ギャップ①</b>: 広告主が正規経路（GET /api/v1/advertiser/rate-cards）で rateCardId を取得できず
 *       運用型キャンペーンを作成できない → {@link PublicRateCardResponse#id()} を公開し、その id で作成できることを検証。</li>
 *   <li><b>ギャップ②</b>: SYSTEM_ADMIN が審査対象の広告主名・scope・クリエイティブを確認できず承認/却下できない
 *       → 審査詳細エンドポイント {@code GET /system-admin/ad-campaigns-operational/{id}} を検証。</li>
 * </ul>
 *
 * <p>金型: {@link OperationalAdCampaignCrudIT}（直接 Controller 呼び出し + SecurityContext 認証・
 * ネイティブ SQL フィクスチャ・相対日付）。実 MySQL（Testcontainers・共有コンテキスト）に対して
 * Controller → Service → Repository を一気通貫で検証する。</p>
 *
 * <p>AC 対応:</p>
 * <ul>
 *   <li>AC-1b.1 広告主が rate-card 一覧の id で運用型キャンペーンを作成 → 201（一気通貫）</li>
 *   <li>AC-1b.2 審査詳細が advertiserName / scopeType / scopeId / creatives[] を返す（クリエイティブ 2 件）</li>
 *   <li>AC-1b.3 存在しない id の審査詳細 → 404（AD_021）</li>
 *   <li>AC-1b.4 非 SYSTEM_ADMIN の審査詳細 → 403（COMMON_002）</li>
 * </ul>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F09.19.1b 契約補完テスト（試練）")
class OperationalAdCampaignReviewContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private AdvertiserDashboardController dashboardController;

    @Autowired
    private OrganizationOperationalAdCampaignController controller;

    @Autowired
    private SystemAdminOperationalAdCampaignController adminController;

    @PersistenceContext
    private EntityManager em;

    private Long orgAId;
    private Long adminAId;
    private Long sysAdminId;
    private Long advertiserAccountAId;
    private Long rateCardId;

    private static final BigDecimal UNIT_PRICE = new BigDecimal("500.0000");
    private static final BigDecimal MIN_DAILY_BUDGET = new BigDecimal("1000.00");

    @BeforeEach
    void setUp() {
        insertRole("SYSTEM_ADMIN", "システム管理者", 1, true);
        insertRole("ADMIN", "管理者", 2, false);

        adminAId = insertUser("f0919-1b-admin-a@example.com");
        sysAdminId = insertUser("f0919-1b-sysadmin@example.com");

        orgAId = insertOrganization("F09191b 組織A");

        insertUserRole(adminAId, roleId("ADMIN"), null, orgAId);
        insertUserRole(sysAdminId, roleId("SYSTEM_ADMIN"), null, null);
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);

        advertiserAccountAId = insertAdvertiserAccount(orgAId, "F09191b 組織A広告主");

        // 有効カード: effective_from = 30 日前・無期限
        rateCardId = insertRateCard("CPM", UNIT_PRICE, MIN_DAILY_BUDGET, -30, null);

        em.flush();
        em.clear();

        setAuthentication(adminAId);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-1b.1 ギャップ①: rate-card 一覧の id で作成できる（一気通貫）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ac1b_1: GET /advertiser/rate-cards が id を返し、その id で運用型キャンペーンを作成 → 201・DRAFT")
    void ac1b_1_公開rateCard一覧のidで作成できる() {
        // 広告主が正規経路で料金カード一覧を取得（id が載っていること）
        List<PublicRateCardResponse> cards = dashboardController.rateCards(null, null).getData();

        assertThat(cards).as("有効な公開料金カードが取得できる").isNotEmpty();
        PublicRateCardResponse target = cards.stream()
                .filter(c -> rateCardId.equals(c.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("公開料金カード一覧に自作カードの id が含まれること"));
        assertThat(target.id()).as("id が公開されること（作成 POST の選択トークン）").isEqualTo(rateCardId);
        assertThat(target.unitPrice()).isEqualByComparingTo(UNIT_PRICE);

        // 取得した id をそのまま作成 POST に渡せる
        CreateOperationalCampaignRequest req = new CreateOperationalCampaignRequest(
                "1b一気通貫キャンペーン", PricingModel.CPM, new BigDecimal("3000.00"),
                LocalDate.now().plusDays(1), LocalDate.now().plusDays(30), target.id());
        OperationalCampaignResponse created = controller.create(orgAId, req).getData();

        assertThat(created.status()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(created.rateCardId())
                .as("公開一覧の id が作成時の rateCardId として通ること").isEqualTo(rateCardId);
        assertThat(created.unitPriceSnapshot())
                .as("選択カードの単価が snapshot として凍結される").isEqualByComparingTo(UNIT_PRICE);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-1b.2 ギャップ②: 審査詳細が広告主帰属 + クリエイティブを返す
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ac1b_2: 審査詳細が advertiserName / scopeType / scopeId / creatives[2] を返す")
    void ac1b_2_審査詳細が広告主帰属とクリエイティブを返す() {
        Long campaignId = insertCampaign(orgAId, "審査対象キャンペーン", "PENDING_REVIEW", rateCardId, UNIT_PRICE);
        Long creative1 = insertCreative(campaignId, "クリエイティブA", "https://example.com/a.png",
                "https://landing.example.com/a", "ACTIVE", "IN_FEED");
        Long creative2 = insertCreative(campaignId, "クリエイティブB", "https://example.com/b.png",
                "https://landing.example.com/b", "DRAFT", "SIDEBAR_RIGHT");
        em.flush();
        em.clear();

        setAuthentication(sysAdminId);
        OperationalCampaignReviewDetailResponse detail =
                adminController.detail(campaignId).getData();

        // キャンペーン本体
        assertThat(detail.id()).isEqualTo(campaignId);
        assertThat(detail.status()).isEqualTo(CampaignStatus.PENDING_REVIEW);

        // 広告主帰属（審査に必須）
        assertThat(detail.advertiserAccountId()).as("広告主アカウント id").isEqualTo(advertiserAccountAId);
        assertThat(detail.advertiserName()).as("広告主表示名（company_name）").isEqualTo("F09191b 組織A広告主");
        assertThat(detail.scopeType()).isEqualTo(ScopeType.ORGANIZATION);
        assertThat(detail.scopeId()).isEqualTo(orgAId);

        // クリエイティブ一覧（2 件・内容確認）
        assertThat(detail.creatives()).as("クリエイティブ 2 件が載る").hasSize(2);
        assertThat(detail.creatives()).extracting(AdCreativeResponse::id)
                .containsExactlyInAnyOrder(creative1, creative2);
        AdCreativeResponse a = detail.creatives().stream()
                .filter(c -> creative1.equals(c.id())).findFirst().orElseThrow();
        assertThat(a.title()).isEqualTo("クリエイティブA");
        assertThat(a.imageUrl()).isEqualTo("https://example.com/a.png");
        assertThat(a.destinationUrl()).isEqualTo("https://landing.example.com/a");
        assertThat(a.status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("ac1b_2: クリエイティブ 0 件のキャンペーンでも詳細は 200・creatives は空配列")
    void ac1b_2_クリエイティブゼロでも空配列で返る() {
        Long campaignId = insertCampaign(orgAId, "クリエイティブなし", "PENDING_REVIEW", rateCardId, UNIT_PRICE);
        em.flush();
        em.clear();

        setAuthentication(sysAdminId);
        OperationalCampaignReviewDetailResponse detail = adminController.detail(campaignId).getData();

        assertThat(detail.creatives()).as("null ではなく空配列").isNotNull().isEmpty();
        assertThat(detail.advertiserName()).isEqualTo("F09191b 組織A広告主");
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-1b.3 存在しない id → 404
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ac1b_3: 存在しない id の審査詳細 → 404（AD_021）")
    void ac1b_3_存在しないidは404() {
        setAuthentication(sysAdminId);

        assertThatThrownBy(() -> adminController.detail(999_999_999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .as("キャンペーン不在は 404 に解決される AD_021")
                        .isEqualTo(AdvertisingErrorCode.AD_021));
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-1b.4 非 SYSTEM_ADMIN → 403
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ac1b_4: 非 SYSTEM_ADMIN による審査詳細 → 403（COMMON_002）")
    void ac1b_4_非システム管理者は403() {
        Long campaignId = insertCampaign(orgAId, "審査対象", "PENDING_REVIEW", rateCardId, UNIT_PRICE);
        em.flush();
        setAuthentication(adminAId); // 組織 ADMIN（SYSTEM_ADMIN ではない）

        assertThatThrownBy(() -> adminController.detail(campaignId))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode())
                        .as("SYSTEM_ADMIN 以外は 403（COMMON_002）").isEqualTo("COMMON_002"));
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー（OperationalAdCampaignCrudIT 踏襲）
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
                                + "VALUES (:email, '運用型', 'テスト', '運用型 テスト', 'ACTIVE', "
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

    private void insertUserRole(Long uid, Long roleId, Long teamIdParam, Long orgIdParam) {
        em.createNativeQuery(
                        "INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                                + "VALUES (:uid, :rid, :tid, :oid, NOW(), NOW())")
                .setParameter("uid", uid)
                .setParameter("rid", roleId)
                .setParameter("tid", teamIdParam)
                .setParameter("oid", orgIdParam)
                .executeUpdate();
    }

    private Long insertAdvertiserAccount(Long orgId, String companyName) {
        em.createNativeQuery(
                        "INSERT INTO advertiser_accounts (scope_type, scope_id, status, company_name, "
                                + "contact_email, billing_method, credit_limit, created_at, updated_at) "
                                + "VALUES ('ORGANIZATION', :oid, 'ACTIVE', :cn, 'ads@example.com', "
                                + "'STRIPE', 100000, NOW(), NOW())")
                .setParameter("oid", orgId)
                .setParameter("cn", companyName)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                        "SELECT id FROM advertiser_accounts WHERE company_name = :cn")
                .setParameter("cn", companyName)
                .getSingleResult()).longValue();
    }

    /** 料金カードを相対日付で挿入する（date-pin 禁則対応）。 */
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

    /**
     * 運用型キャンペーンを直接挿入する。start_date / end_date は JVM の {@link LocalDate#now()} を bind する
     * （TZ 差による日付境界フレーク根絶。{@link OperationalAdCampaignCrudIT} 参照）。
     */
    private Long insertCampaign(Long orgId, String name, String status, Long cardId, BigDecimal snapshot) {
        em.createNativeQuery(
                        "INSERT INTO ad_campaigns (advertiser_account_id, name, status, pricing_model, "
                                + "daily_budget, start_date, end_date, rate_card_id, unit_price_snapshot, "
                                + "created_at, updated_at) "
                                + "VALUES ((SELECT id FROM advertiser_accounts WHERE scope_type='ORGANIZATION' "
                                + "AND scope_id=:oid AND deleted_at IS NULL), :name, :status, 'CPM', :budget, "
                                + ":startDate, :endDate, "
                                + ":cardId, :snapshot, NOW(), NOW())")
                .setParameter("oid", orgId)
                .setParameter("name", name)
                .setParameter("status", status)
                .setParameter("budget", MIN_DAILY_BUDGET)
                .setParameter("startDate", LocalDate.now().plusDays(1))
                .setParameter("endDate", LocalDate.now().plusDays(30))
                .setParameter("cardId", cardId)
                .setParameter("snapshot", snapshot)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM ad_campaigns").getSingleResult()).longValue();
    }

    /** クリエイティブ（ads 行）を挿入する。created_at/updated_at は NOW()（datetime 列は無し・TZ 非依存）。 */
    private Long insertCreative(Long campaignId, String title, String imageUrl,
                                String destinationUrl, String status, String placement) {
        em.createNativeQuery(
                        "INSERT INTO ads (campaign_id, title, image_url, destination_url, status, placement, "
                                + "created_at, updated_at) "
                                + "VALUES (:cid, :title, :img, :dest, :status, :placement, NOW(), NOW())")
                .setParameter("cid", campaignId)
                .setParameter("title", title)
                .setParameter("img", imageUrl)
                .setParameter("dest", destinationUrl)
                .setParameter("status", status)
                .setParameter("placement", placement)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM ads").getSingleResult()).longValue();
    }
}
