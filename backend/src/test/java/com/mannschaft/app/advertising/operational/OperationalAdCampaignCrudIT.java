package com.mannschaft.app.advertising.operational;

import com.mannschaft.app.advertising.AdvertisingErrorCode;
import com.mannschaft.app.advertising.PricingModel;
import com.mannschaft.app.advertising.controller.OrganizationOperationalAdCampaignController;
import com.mannschaft.app.advertising.controller.SystemAdminOperationalAdCampaignController;
import com.mannschaft.app.advertising.dto.CreateOperationalCampaignRequest;
import com.mannschaft.app.advertising.dto.OperationalCampaignResponse;
import com.mannschaft.app.advertising.dto.RejectOperationalCampaignRequest;
import com.mannschaft.app.advertising.entity.AdCampaignEntity.CampaignStatus;
import com.mannschaft.app.advertising.service.AdRateCardService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F09.19.1 運用型キャンペーン CRUD API 契約テスト（試練 / red 先行）。
 *
 * <p>正本: {@code docs/features/F09.19_ad_slot_serving.md} §6.5（CRUD 契約）・§6.1（審査）・
 * §15（エラーコード）・§16 F09.19.1（受け入れ条件）。</p>
 *
 * <p>金型: {@code AppearanceSettingsControllerIntegrationTest}（直接 Controller 呼び出し +
 * SecurityContext 認証）と {@code BulletinThreadVisibilityResolverIntegrationTest}
 * （roles / user_roles / organizations のネイティブ SQL フィクスチャ）。実 MySQL
 * （Testcontainers・共有コンテキスト）に対して Controller → Service → Repository を一気通貫で検証する。</p>
 *
 * <p>AC 対応（テストメソッド名の ac 番号と 1:1）:</p>
 * <ul>
 *   <li>AC-1.1 作成 201 + snapshot 確定 + DRAFT</li>
 *   <li>AC-1.2 状態遷移 submit/approve/pause/resume/end</li>
 *   <li>AC-1.3 一覧 status フィルタ・page/size・created_at DESC・PagedResponse 正準</li>
 *   <li>AC-1.4 編集可否と snapshot 再確定規則</li>
 *   <li>AC-1.5 バリデーション・状態違反（AD_027/028/030/031）</li>
 *   <li>AC-1.6 認可（他組織 403・広告主未登録 403）</li>
 *   <li>AC-1.10 境界（startDate=endDate / dailyBudget=min ちょうど）</li>
 *   <li>AC-1.11 reject 理由永続化・再 submit で NULL クリア</li>
 *   <li>AC-1.12 参照中料金カード削除の AD_034 防御</li>
 * </ul>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F09.19.1 運用型キャンペーン CRUD API 契約テスト（試練）")
class OperationalAdCampaignCrudIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private OrganizationOperationalAdCampaignController controller;

    @Autowired
    private SystemAdminOperationalAdCampaignController adminController;

    @Autowired
    private AdRateCardService adRateCardService;

    @PersistenceContext
    private EntityManager em;

    private Long orgAId;
    private Long orgBId;
    private Long adminAId;
    private Long adminBId;
    private Long sysAdminId;
    private Long advertiserAccountAId;
    /** 有効な CPM 料金カード（unit_price=500.0000 / min_daily_budget=1000）。 */
    private Long rateCardId;
    /** 有効な CPM 料金カード 2 枚目（unit_price=800.0000 / min_daily_budget=1000。snapshot 再確定用）。 */
    private Long rateCard2Id;
    /** 期間外（過去に失効）の CPM 料金カード。 */
    private Long expiredRateCardId;

    private static final BigDecimal UNIT_PRICE = new BigDecimal("500.0000");
    private static final BigDecimal UNIT_PRICE_2 = new BigDecimal("800.0000");
    private static final BigDecimal MIN_DAILY_BUDGET = new BigDecimal("1000.00");

    @BeforeEach
    void setUp() {
        insertRole("SYSTEM_ADMIN", "システム管理者", 1, true);
        insertRole("ADMIN", "管理者", 2, false);
        Long adminRoleId = roleId("ADMIN");
        Long sysAdminRoleId = roleId("SYSTEM_ADMIN");

        adminAId = insertUser("op-admin-a@example.com");
        adminBId = insertUser("op-admin-b@example.com");
        sysAdminId = insertUser("op-sysadmin@example.com");

        orgAId = insertOrganization("F09191 組織A");
        orgBId = insertOrganization("F09191 組織B");

        insertUserRole(adminAId, adminRoleId, null, orgAId);
        insertUserRole(adminBId, adminRoleId, null, orgBId);
        insertUserRole(sysAdminId, sysAdminRoleId, null, null);

        advertiserAccountAId = insertAdvertiserAccount(orgAId, "組織A広告主");
        // 組織 B は広告主アカウントあり（クロステナント検証用）。広告主未登録テストでは組織 C を別途作る
        insertAdvertiserAccount(orgBId, "組織B広告主");

        // 有効カード: effective_from = 30 日前・無期限
        rateCardId = insertRateCard("CPM", UNIT_PRICE, MIN_DAILY_BUDGET, -30, null);
        rateCard2Id = insertRateCard("CPM", UNIT_PRICE_2, MIN_DAILY_BUDGET, -30, null);
        // 期間外カード: 60 日前開始 → 10 日前に失効
        expiredRateCardId = insertRateCard("CPM", new BigDecimal("300.0000"), MIN_DAILY_BUDGET, -60, -10);

        em.flush();
        em.clear();

        setAuthentication(adminAId);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-1.1 作成
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ac1_1: ORGANIZATION の ADMIN が有効 rate_card 指定で作成 → 201・snapshot=unit_price・status=DRAFT")
    void ac1_1_キャンペーン作成でsnapshotが確定しDRAFTで返る() {
        OperationalCampaignResponse res = controller.create(orgAId, validCreateRequest()).getData();

        assertThat(res.id()).as("採番された id が返ること").isNotNull();
        assertThat(res.status()).as("作成直後は DRAFT").isEqualTo(CampaignStatus.DRAFT);
        assertThat(res.unitPriceSnapshot())
                .as("unit_price_snapshot が選択 rate_card の unit_price と一致（申込時凍結）")
                .isEqualByComparingTo(UNIT_PRICE);
        assertThat(res.rateCardId()).isEqualTo(rateCardId);
        assertThat(res.rejectReason()).as("新規作成時は差戻し理由なし").isNull();
        assertThat(res.reportSuspendedAt()).as("F09.19.9 実装まで常に null").isNull();

        // HTTP 201 は @ResponseStatus(CREATED) で構造的に保証されるため、注釈を契約として固定する
        ResponseStatus rs = extractResponseStatus("create");
        assertThat(rs).as("create に @ResponseStatus が付与されていること").isNotNull();
        assertThat(rs.value()).isEqualTo(HttpStatus.CREATED);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-1.2 状態遷移
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ac1_2: submit→PENDING_REVIEW / approve→ACTIVE / pause→PAUSED / resume→ACTIVE / end→ENDED")
    void ac1_2_状態遷移が正順で成立する() {
        Long campaignId = insertCampaign(orgAId, "遷移テスト", "DRAFT", rateCardId, UNIT_PRICE);

        assertThat(controller.submit(orgAId, campaignId).getData().status())
                .as("submit で PENDING_REVIEW").isEqualTo(CampaignStatus.PENDING_REVIEW);

        setAuthentication(sysAdminId);
        assertThat(adminController.approve(campaignId).getData().status())
                .as("SYSTEM_ADMIN approve で ACTIVE").isEqualTo(CampaignStatus.ACTIVE);

        setAuthentication(adminAId);
        assertThat(controller.pause(orgAId, campaignId).getData().status())
                .as("pause で PAUSED").isEqualTo(CampaignStatus.PAUSED);
        assertThat(controller.resume(orgAId, campaignId).getData().status())
                .as("resume で ACTIVE").isEqualTo(CampaignStatus.ACTIVE);
        assertThat(controller.end(orgAId, campaignId).getData().status())
                .as("end で ENDED（終端）").isEqualTo(CampaignStatus.ENDED);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-1.3 一覧
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ac1_3: status=DRAFT フィルタで DRAFT のみ・page/size が効き created_at DESC・PagedResponse 正準")
    void ac1_3_一覧のフィルタとページングと並び順() {
        // created_at を 1 秒刻みでずらして挿入（古い→新しい）
        Long draft1 = insertCampaignWithCreatedAtOffset(orgAId, "DRAFT-1", "DRAFT", -30);
        Long draft2 = insertCampaignWithCreatedAtOffset(orgAId, "DRAFT-2", "DRAFT", -20);
        Long draft3 = insertCampaignWithCreatedAtOffset(orgAId, "DRAFT-3", "DRAFT", -10);
        insertCampaignWithCreatedAtOffset(orgAId, "ACTIVE-1", "ACTIVE", -5);
        em.flush();
        em.clear();

        PagedResponse<OperationalCampaignResponse> pageRes =
                controller.list(orgAId, CampaignStatus.DRAFT, 0, 2);

        // PagedResponse 正準: {"data":[...],"meta":{total,page,size,totalPages}}
        assertThat(pageRes.getMeta().getTotal()).as("DRAFT のみが total に数えられる").isEqualTo(3);
        assertThat(pageRes.getMeta().getPage()).isEqualTo(0);
        assertThat(pageRes.getMeta().getSize()).isEqualTo(2);
        assertThat(pageRes.getMeta().getTotalPages()).isEqualTo(2);

        List<OperationalCampaignResponse> data = pageRes.getData();
        assertThat(data).hasSize(2);
        assertThat(data).allSatisfy(c ->
                assertThat(c.status()).as("status=DRAFT フィルタで DRAFT のみ").isEqualTo(CampaignStatus.DRAFT));
        // created_at DESC: 最新（draft3）→ 次点（draft2）
        assertThat(data.get(0).id()).as("created_at DESC の先頭は最新の DRAFT-3").isEqualTo(draft3);
        assertThat(data.get(1).id()).isEqualTo(draft2);

        // 2 ページ目に最古の DRAFT-1
        PagedResponse<OperationalCampaignResponse> page2 =
                controller.list(orgAId, CampaignStatus.DRAFT, 1, 2);
        assertThat(page2.getData()).extracting(OperationalCampaignResponse::id)
                .containsExactly(draft1);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-1.4 編集（PUT）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-1.4 PUT 編集可否と snapshot 再確定")
    class Ac1_4_Update {

        @Test
        @DisplayName("ac1_4: DRAFT で rateCardId 変更 → unit_price_snapshot が新カードの単価で再確定")
        void ac1_4_DRAFTのrateCardId変更でsnapshot再確定() {
            Long campaignId = insertCampaign(orgAId, "編集テストDRAFT", "DRAFT", rateCardId, UNIT_PRICE);

            OperationalCampaignResponse res = controller.update(orgAId, campaignId,
                    requestWith(rateCard2Id, MIN_DAILY_BUDGET, 1, 30)).getData();

            assertThat(res.rateCardId()).isEqualTo(rateCard2Id);
            assertThat(res.unitPriceSnapshot())
                    .as("DRAFT の rateCardId 変更時のみ snapshot を再確定（§6.5）")
                    .isEqualByComparingTo(UNIT_PRICE_2);
        }

        @Test
        @DisplayName("ac1_4: PAUSED で name/dailyBudget/endDate 変更 → 200 かつ snapshot 不変")
        void ac1_4_PAUSEDの許可フィールド変更はsnapshot不変() {
            Long campaignId = insertCampaign(orgAId, "編集テストPAUSED", "PAUSED", rateCardId, UNIT_PRICE);

            // 「startDate 不変」の意図を厳密に表現するため、永続化済みの現 startDate を読み戻して再送する
            // （日付を独立に再計算して一致を仮定しない = 日付境界フレーク根絶）。endDate も読み戻し値基準で組む。
            LocalDate currentStartDate = controller.get(orgAId, campaignId).getData().startDate();
            LocalDate newEndDate = currentStartDate.plusDays(59); // 開始日以降であればよい（endDate は等価比較の対象外）

            // 現値と同じ rateCardId / pricingModel / startDate のまま、name・dailyBudget・endDate のみ変更
            CreateOperationalCampaignRequest req = new CreateOperationalCampaignRequest(
                    "改名後キャンペーン", PricingModel.CPM, new BigDecimal("2000.00"),
                    currentStartDate, newEndDate, rateCardId);

            OperationalCampaignResponse res = controller.update(orgAId, campaignId, req).getData();

            assertThat(res.name()).isEqualTo("改名後キャンペーン");
            assertThat(res.dailyBudget()).isEqualByComparingTo(new BigDecimal("2000.00"));
            assertThat(res.endDate()).isEqualTo(newEndDate);
            assertThat(res.unitPriceSnapshot())
                    .as("PAUSED 編集では snapshot 不変（resume 後の課金単価保証）")
                    .isEqualByComparingTo(UNIT_PRICE);
        }

        @Test
        @DisplayName("ac1_4: PAUSED で rateCardId を現値と異なる値に変更 → 409 / AD_027")
        void ac1_4_PAUSEDのrateCardId変更はAD_027() {
            Long campaignId = insertCampaign(orgAId, "編集不可1", "PAUSED", rateCardId, UNIT_PRICE);

            assertThatThrownBy(() -> controller.update(orgAId, campaignId,
                    requestWith(rateCard2Id, MIN_DAILY_BUDGET, 1, 30)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(AdvertisingErrorCode.AD_027));
        }

        @Test
        @DisplayName("ac1_4: PAUSED で pricingModel を現値と異なる値に変更 → 409 / AD_027")
        void ac1_4_PAUSEDのpricingModel変更はAD_027() {
            Long campaignId = insertCampaign(orgAId, "編集不可2", "PAUSED", rateCardId, UNIT_PRICE);
            // CPC カード（pricingModel 一致検証を通すため CPC で有効なカードを用意）
            Long cpcCardId = insertRateCard("CPC", new BigDecimal("60.0000"), MIN_DAILY_BUDGET, -30, null);

            CreateOperationalCampaignRequest req = new CreateOperationalCampaignRequest(
                    "編集不可2", PricingModel.CPC, MIN_DAILY_BUDGET,
                    campaignStartDate(), LocalDate.now().plusDays(30), cpcCardId);

            assertThatThrownBy(() -> controller.update(orgAId, campaignId, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(AdvertisingErrorCode.AD_027));
        }

        @Test
        @DisplayName("ac1_4: PAUSED で startDate を現値と異なる値に変更 → 409 / AD_027")
        void ac1_4_PAUSEDのstartDate変更はAD_027() {
            Long campaignId = insertCampaign(orgAId, "編集不可3", "PAUSED", rateCardId, UNIT_PRICE);

            CreateOperationalCampaignRequest req = new CreateOperationalCampaignRequest(
                    "編集不可3", PricingModel.CPM, MIN_DAILY_BUDGET,
                    LocalDate.now().plusDays(10), LocalDate.now().plusDays(30), rateCardId);

            assertThatThrownBy(() -> controller.update(orgAId, campaignId, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(AdvertisingErrorCode.AD_027));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-1.5 異常系（バリデーション・状態違反）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-1.5 バリデーション・状態違反")
    class Ac1_5_Validation {

        @Test
        @DisplayName("ac1_5: dailyBudget < min_daily_budget → 400 / AD_028")
        void ac1_5_日予算が最低日予算未満はAD_028() {
            CreateOperationalCampaignRequest req = requestWith(
                    rateCardId, MIN_DAILY_BUDGET.subtract(BigDecimal.ONE), 1, 30);

            assertThatThrownBy(() -> controller.create(orgAId, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(AdvertisingErrorCode.AD_028));
        }

        @Test
        @DisplayName("ac1_5: 期間外 rate_card 指定 → 400 / AD_031")
        void ac1_5_期間外rate_cardはAD_031() {
            CreateOperationalCampaignRequest req = requestWith(
                    expiredRateCardId, MIN_DAILY_BUDGET, 1, 30);

            assertThatThrownBy(() -> controller.create(orgAId, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(AdvertisingErrorCode.AD_031));
        }

        @Test
        @DisplayName("ac1_5: startDate > endDate → 400 / AD_030")
        void ac1_5_開始日が終了日より後はAD_030() {
            CreateOperationalCampaignRequest req = new CreateOperationalCampaignRequest(
                    "期間不正", PricingModel.CPM, MIN_DAILY_BUDGET,
                    LocalDate.now().plusDays(30), LocalDate.now().plusDays(1), rateCardId);

            assertThatThrownBy(() -> controller.create(orgAId, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(AdvertisingErrorCode.AD_030));
        }

        @Test
        @DisplayName("ac1_5: ACTIVE 中の PUT → 409 / AD_027")
        void ac1_5_ACTIVE中のPUTはAD_027() {
            Long campaignId = insertCampaign(orgAId, "ACTIVE編集不可", "ACTIVE", rateCardId, UNIT_PRICE);

            assertThatThrownBy(() -> controller.update(orgAId, campaignId,
                    requestWith(rateCardId, MIN_DAILY_BUDGET, 1, 30)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(AdvertisingErrorCode.AD_027));
        }

        @Test
        @DisplayName("ac1_5: ENDED への resume → 409 / AD_027")
        void ac1_5_ENDEDへのresumeはAD_027() {
            Long campaignId = insertCampaign(orgAId, "ENDED再開不可", "ENDED", rateCardId, UNIT_PRICE);

            assertThatThrownBy(() -> controller.resume(orgAId, campaignId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(AdvertisingErrorCode.AD_027));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-1.6 認可
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-1.6 認可（他組織 403・広告主未登録 403）")
    class Ac1_6_Authorization {

        @Test
        @DisplayName("ac1_6: 他組織の ADMIN による一覧 → 403（存在有無を問わず）")
        void ac1_6_他組織ADMINの一覧は403() {
            setAuthentication(adminBId); // 組織 B の ADMIN が組織 A の URL を叩く

            assertThatThrownBy(() -> controller.list(orgAId, null, 0, 20))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertForbidden((BusinessException) e));
        }

        @Test
        @DisplayName("ac1_6: 他組織の ADMIN による詳細 → 403（存在有無を問わず）")
        void ac1_6_他組織ADMINの詳細は403() {
            Long campaignId = insertCampaign(orgAId, "組織Aの機密", "DRAFT", rateCardId, UNIT_PRICE);
            setAuthentication(adminBId);

            assertThatThrownBy(() -> controller.get(orgAId, campaignId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertForbidden((BusinessException) e));
        }

        @Test
        @DisplayName("ac1_6: 他組織の ADMIN による更新 → 403（存在有無を問わず）")
        void ac1_6_他組織ADMINの更新は403() {
            Long campaignId = insertCampaign(orgAId, "組織Aの機密2", "DRAFT", rateCardId, UNIT_PRICE);
            setAuthentication(adminBId);

            assertThatThrownBy(() -> controller.update(orgAId, campaignId,
                    requestWith(rateCardId, MIN_DAILY_BUDGET, 1, 30)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertForbidden((BusinessException) e));
        }

        @Test
        @DisplayName("ac1_6: 広告主未登録 scope での作成 → 403")
        void ac1_6_広告主未登録scopeでの作成は403() {
            // 広告主アカウントを持たない組織 C とその ADMIN
            Long orgCId = insertOrganization("F09191 組織C(広告主なし)");
            Long adminCId = insertUser("op-admin-c@example.com");
            insertUserRole(adminCId, roleId("ADMIN"), null, orgCId);
            em.flush();
            setAuthentication(adminCId);

            assertThatThrownBy(() -> controller.create(orgCId, validCreateRequest()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertForbidden((BusinessException) e));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-1.10 境界
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ac1_10: startDate = endDate（1 日キャンペーン）→ 201")
    void ac1_10_開始日と終了日が同日は作成できる() {
        LocalDate day = LocalDate.now().plusDays(7);
        CreateOperationalCampaignRequest req = new CreateOperationalCampaignRequest(
                "1日キャンペーン", PricingModel.CPM, MIN_DAILY_BUDGET, day, day, rateCardId);

        OperationalCampaignResponse res = controller.create(orgAId, req).getData();

        assertThat(res.status()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(res.startDate()).isEqualTo(day);
        assertThat(res.endDate()).isEqualTo(day);
    }

    @Test
    @DisplayName("ac1_10: dailyBudget = min_daily_budget ちょうど → 201")
    void ac1_10_日予算が最低日予算ちょうどは作成できる() {
        CreateOperationalCampaignRequest req = requestWith(rateCardId, MIN_DAILY_BUDGET, 1, 30);

        OperationalCampaignResponse res = controller.create(orgAId, req).getData();

        assertThat(res.status()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(res.dailyBudget()).isEqualByComparingTo(MIN_DAILY_BUDGET);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-1.11 reject 差戻し
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ac1_11: reject（理由付き）→ DRAFT + reject_reason 永続化 + レスポンス反映。再 submit → rejectReason が null")
    void ac1_11_rejectの理由永続化と再submitでのクリア() {
        Long campaignId = insertCampaign(orgAId, "差戻しテスト", "PENDING_REVIEW", rateCardId, UNIT_PRICE);

        setAuthentication(sysAdminId);
        OperationalCampaignResponse rejected = adminController.reject(campaignId,
                new RejectOperationalCampaignRequest("画像が不鮮明です")).getData();

        assertThat(rejected.status()).as("reject で DRAFT に差戻し").isEqualTo(CampaignStatus.DRAFT);
        assertThat(rejected.rejectReason()).as("レスポンスに差戻し理由が載る").isEqualTo("画像が不鮮明です");

        // ad_campaigns.reject_reason へ永続化されていること（DB 実列を直接確認）
        em.flush();
        em.clear();
        Object persisted = em.createNativeQuery(
                        "SELECT reject_reason FROM ad_campaigns WHERE id = :id")
                .setParameter("id", campaignId)
                .getSingleResult();
        assertThat(persisted).as("reject_reason が DB に永続化される").isEqualTo("画像が不鮮明です");

        // 再 submit で rejectReason が NULL クリア
        setAuthentication(adminAId);
        OperationalCampaignResponse resubmitted = controller.submit(orgAId, campaignId).getData();
        assertThat(resubmitted.status()).isEqualTo(CampaignStatus.PENDING_REVIEW);
        assertThat(resubmitted.rejectReason()).as("再 submit で reject_reason が NULL クリア").isNull();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-1.12 料金カード削除防御
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ac1_12: 運用型キャンペーンが参照する未来日 rate_card の DELETE → 409 / AD_034（FK violation 500 にしない）")
    void ac1_12_参照中の料金カード削除はAD_034() {
        // 未来日カード（AD_009 の過去日ガードに掛からない）を運用型キャンペーンが参照
        Long futureCardId = insertRateCard("CPM", new BigDecimal("700.0000"), MIN_DAILY_BUDGET, +10, null);
        insertCampaign(orgAId, "参照キャンペーン", "DRAFT", futureCardId, new BigDecimal("700.0000"));
        em.flush();

        assertThatThrownBy(() -> adRateCardService.delete(futureCardId))
                .as("参照中カードの削除は FK violation 500 ではなく 409 / AD_034 で拒否")
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(AdvertisingErrorCode.AD_034));
    }

    @Test
    @DisplayName("ac1_12: 参照ゼロの未来日 rate_card の DELETE → 従来どおり削除成功（回帰番人）")
    void ac1_12_参照ゼロの料金カードは削除できる() {
        Long futureCardId = insertRateCard("CPM", new BigDecimal("700.0000"), MIN_DAILY_BUDGET, +10, null);
        em.flush();

        adRateCardService.delete(futureCardId);

        Number count = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM ad_rate_cards WHERE id = :id")
                .setParameter("id", futureCardId)
                .getSingleResult();
        assertThat(count.longValue()).as("参照ゼロのカードは物理削除される").isZero();
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    /** 403 検証: COMMON_002（checkAdminOrAbove 既定）または AD 系 403 コードのいずれかで拒否されること。 */
    private void assertForbidden(BusinessException e) {
        String code = e.getErrorCode().getCode();
        assertThat(code)
                .as("403 に解決されるエラーコードで拒否されること（COMMON_002 = 認可拒否）")
                .isEqualTo("COMMON_002");
    }

    private CreateOperationalCampaignRequest validCreateRequest() {
        return requestWith(rateCardId, new BigDecimal("3000.00"), 1, 30);
    }

    /** startDate = 今日+startOffsetDays / endDate = 今日+endOffsetDays の相対日付リクエスト（date-pin 禁則対応）。 */
    private CreateOperationalCampaignRequest requestWith(
            Long cardId, BigDecimal dailyBudget, int startOffsetDays, int endOffsetDays) {
        return new CreateOperationalCampaignRequest(
                "夏季キャンペーン", PricingModel.CPM, dailyBudget,
                LocalDate.now().plusDays(startOffsetDays), LocalDate.now().plusDays(endOffsetDays), cardId);
    }

    /** フィクスチャで挿入するキャンペーンの start_date（明日）。 */
    private LocalDate campaignStartDate() {
        return LocalDate.now().plusDays(1);
    }

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private ResponseStatus extractResponseStatus(String methodName) {
        for (var m : OrganizationOperationalAdCampaignController.class.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                return m.getAnnotation(ResponseStatus.class);
            }
        }
        return null;
    }

    // ─── ネイティブ SQL フィクスチャ（BulletinThreadVisibilityResolverIntegrationTest 踏襲） ───

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

    /**
     * 料金カードを相対日付で挿入する（date-pin 禁則対応）。
     *
     * @param fromOffsetDays  effective_from = 本日 + fromOffsetDays 日
     * @param untilOffsetDays effective_until = 本日 + untilOffsetDays 日（null は無期限）
     */
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
     * 運用型キャンペーンを直接挿入する（状態遷移・編集テストの前提行）。
     *
     * <p>start_date / end_date は JVM の {@link LocalDate#now()} を bind する（MySQL {@code CURDATE()}
     * を使わない）。理由: PAUSED 編集の不可変フィールドガードは {@code request.startDate} と
     * 永続 {@code start_date} の完全一致を要求する。フィクスチャを {@code CURDATE()}（コンテナ TZ = UTC）で、
     * リクエストを JVM {@code LocalDate.now()}（マシン TZ）で作ると、日付境界（TZ 差・深夜跨ぎ）で
     * 両者が 1 日ズレて偽 AD_027 になる間欠失敗が起きる。日付源を JVM に統一して根絶する。</p>
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

    /** created_at を秒オフセットで指定してキャンペーンを挿入する（一覧の並び順検証用）。start_date は JVM 日付を bind。 */
    private Long insertCampaignWithCreatedAtOffset(Long orgId, String name, String status, int secondsOffset) {
        em.createNativeQuery(
                        "INSERT INTO ad_campaigns (advertiser_account_id, name, status, pricing_model, "
                                + "daily_budget, start_date, end_date, rate_card_id, unit_price_snapshot, "
                                + "created_at, updated_at) "
                                + "VALUES ((SELECT id FROM advertiser_accounts WHERE scope_type='ORGANIZATION' "
                                + "AND scope_id=:oid AND deleted_at IS NULL), :name, :status, 'CPM', :budget, "
                                + ":startDate, :endDate, "
                                + ":cardId, :snapshot, "
                                + "DATE_ADD(NOW(), INTERVAL " + secondsOffset + " SECOND), NOW())")
                .setParameter("oid", orgId)
                .setParameter("name", name)
                .setParameter("status", status)
                .setParameter("budget", MIN_DAILY_BUDGET)
                .setParameter("startDate", LocalDate.now().plusDays(1))
                .setParameter("endDate", LocalDate.now().plusDays(30))
                .setParameter("cardId", rateCardId)
                .setParameter("snapshot", UNIT_PRICE)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM ad_campaigns").getSingleResult()).longValue();
    }
}
