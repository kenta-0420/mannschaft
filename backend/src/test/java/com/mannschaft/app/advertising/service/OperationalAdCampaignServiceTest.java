package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.AdvertisingErrorCode;
import com.mannschaft.app.advertising.PricingModel;
import com.mannschaft.app.advertising.dto.AdCreativeResponse;
import com.mannschaft.app.advertising.dto.CreateOperationalCampaignRequest;
import com.mannschaft.app.advertising.dto.OperationalCampaignResponse;
import com.mannschaft.app.advertising.dto.OperationalCampaignReviewDetailResponse;
import com.mannschaft.app.advertising.entity.AdCampaignEntity;
import com.mannschaft.app.advertising.entity.AdCampaignEntity.CampaignStatus;
import com.mannschaft.app.advertising.entity.AdRateCardEntity;
import com.mannschaft.app.advertising.entity.AdvertiserAccountEntity;
import com.mannschaft.app.advertising.repository.AdCampaignRepository;
import com.mannschaft.app.advertising.repository.AdEntityRepository;
import com.mannschaft.app.advertising.repository.AdRateCardRepository;
import com.mannschaft.app.advertising.repository.AdvertiserAccountRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.ScopeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * F09.19.1 {@link OperationalAdCampaignService} ドメイン単体テスト（試練 / red 先行・DB 不使用）。
 *
 * <p>状態機械・編集可否マトリクス・snapshot 再確定規則・バリデーション規則（正本 §6.5）を
 * Mockito モックで検証する。骨格実装は {@link UnsupportedOperationException} を投げるため
 * 全テストが red（実装が無いための失敗）となる。</p>
 *
 * <p>時刻は {@link Clock#fixed} を注入し、日付は clock 基準の相対で組む
 * （TEST_CONVENTION §2.4 date-pin 禁則の安全形 1）。
 * 未使用スタブによる UnnecessaryStubbing ノイズを避けるため LENIENT
 * （feedback_full_shard_test_isolation_flaky の lenient 化前例）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OperationalAdCampaignService ドメイン単体テスト (F09.19.1 試練)")
class OperationalAdCampaignServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    /** clock 基準の「今日」。全ての日付はここからの相対で組む。 */
    private static final LocalDate TODAY = LocalDate.now(FIXED_CLOCK);

    private static final Long ORG_ID = 100L;
    private static final Long ACCOUNT_ID = 77L;
    private static final Long USER_ID = 1L;
    private static final Long ADMIN_USER_ID = 9L;
    private static final Long CAMPAIGN_ID = 45L;
    private static final Long RATE_CARD_ID = 3L;
    private static final Long RATE_CARD_2_ID = 4L;

    private static final BigDecimal UNIT_PRICE = new BigDecimal("500.0000");
    private static final BigDecimal UNIT_PRICE_2 = new BigDecimal("800.0000");
    private static final BigDecimal MIN_DAILY_BUDGET = new BigDecimal("1000");

    @Mock
    private AdCampaignRepository adCampaignRepository;
    @Mock
    private AdvertiserAccountRepository advertiserAccountRepository;
    @Mock
    private AdRateCardRepository adRateCardRepository;
    @Mock
    private AdEntityRepository adEntityRepository;
    @Mock
    private AuditLogService auditLogService;

    private OperationalAdCampaignService service;

    @BeforeEach
    void setUp() {
        service = new OperationalAdCampaignService(
                adCampaignRepository, advertiserAccountRepository, adRateCardRepository,
                adEntityRepository, auditLogService, FIXED_CLOCK);

        // F09.19.5: scope（ORGANIZATION, ORG_ID）→ 広告主アカウント（id=ACCOUNT_ID）解決を stub
        given(advertiserAccountRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(ScopeType.ORGANIZATION, ORG_ID))
                .willReturn(Optional.of(account()));
        given(advertiserAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account()));

        given(adRateCardRepository.findById(RATE_CARD_ID)).willReturn(Optional.of(rateCard(RATE_CARD_ID, UNIT_PRICE)));
        given(adRateCardRepository.findById(RATE_CARD_2_ID)).willReturn(Optional.of(rateCard(RATE_CARD_2_ID, UNIT_PRICE_2)));
        given(adCampaignRepository.save(any(AdCampaignEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));
    }

    /** scope=ORGANIZATION / scope_id=ORG_ID の広告主アカウント（id=ACCOUNT_ID）。 */
    private AdvertiserAccountEntity account() {
        return AdvertiserAccountEntity.builder()
                .id(ACCOUNT_ID)
                .scopeType(ScopeType.ORGANIZATION)
                .scopeId(ORG_ID)
                .build();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-1.1 作成: snapshot 確定
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ac1_1: 作成で unit_price_snapshot が rate_card.unit_price から確定し status=DRAFT")
    void ac1_1_キャンペーン作成でsnapshotが確定する() {
        stubCampaignLookup(campaign(CampaignStatus.DRAFT));

        OperationalCampaignResponse res = service.create(
                ScopeType.ORGANIZATION, ORG_ID, USER_ID, validRequest(RATE_CARD_ID));

        assertThat(res.status()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(res.unitPriceSnapshot())
                .as("申込時単価スナップショットの凍結（以降の料金改定の影響を受けない）")
                .isEqualByComparingTo(UNIT_PRICE);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-1.2 状態機械（正常遷移）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-1.2 状態機械（正常遷移）")
    class Ac1_2_StateMachine {

        @Test
        @DisplayName("ac1_2: DRAFT の submit → PENDING_REVIEW")
        void ac1_2_submitでPENDING_REVIEWになる() {
            stubCampaignLookup(campaign(CampaignStatus.DRAFT));

            OperationalCampaignResponse res = service.submit(
                    ScopeType.ORGANIZATION, ORG_ID, CAMPAIGN_ID, USER_ID);

            assertThat(res.status()).isEqualTo(CampaignStatus.PENDING_REVIEW);
        }

        @Test
        @DisplayName("ac1_2: PENDING_REVIEW の approve → ACTIVE")
        void ac1_2_approveでACTIVEになる() {
            stubCampaignLookup(campaign(CampaignStatus.PENDING_REVIEW));

            OperationalCampaignResponse res = service.approve(CAMPAIGN_ID, ADMIN_USER_ID);

            assertThat(res.status()).isEqualTo(CampaignStatus.ACTIVE);
        }

        @Test
        @DisplayName("ac1_2: ACTIVE の pause → PAUSED / PAUSED の resume → ACTIVE")
        void ac1_2_pauseとresumeが対で遷移する() {
            stubCampaignLookup(campaign(CampaignStatus.ACTIVE));
            assertThat(service.pause(ScopeType.ORGANIZATION, ORG_ID, CAMPAIGN_ID, USER_ID).status())
                    .isEqualTo(CampaignStatus.PAUSED);

            stubCampaignLookup(campaign(CampaignStatus.PAUSED));
            assertThat(service.resume(ScopeType.ORGANIZATION, ORG_ID, CAMPAIGN_ID, USER_ID).status())
                    .isEqualTo(CampaignStatus.ACTIVE);
        }

        @Test
        @DisplayName("ac1_2: ACTIVE / PAUSED の end → ENDED（終端）")
        void ac1_2_endでENDEDになる() {
            stubCampaignLookup(campaign(CampaignStatus.ACTIVE));
            assertThat(service.end(ScopeType.ORGANIZATION, ORG_ID, CAMPAIGN_ID, USER_ID).status())
                    .isEqualTo(CampaignStatus.ENDED);

            stubCampaignLookup(campaign(CampaignStatus.PAUSED));
            assertThat(service.end(ScopeType.ORGANIZATION, ORG_ID, CAMPAIGN_ID, USER_ID).status())
                    .isEqualTo(CampaignStatus.ENDED);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-1.5 状態機械（遷移条件外 → AD_027）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-1.5 遷移条件外は 409 / AD_027")
    class Ac1_5_InvalidTransition {

        @Test
        @DisplayName("ac1_5: ENDED への resume → AD_027")
        void ac1_5_ENDEDへのresumeはAD_027() {
            stubCampaignLookup(campaign(CampaignStatus.ENDED));

            assertThatThrownBy(() -> service.resume(ScopeType.ORGANIZATION, ORG_ID, CAMPAIGN_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(AdvertisingErrorCode.AD_027));
        }

        @Test
        @DisplayName("ac1_5: ACTIVE の submit / DRAFT の approve / DRAFT の pause → AD_027")
        void ac1_5_遷移条件外の操作はAD_027() {
            stubCampaignLookup(campaign(CampaignStatus.ACTIVE));
            assertThatThrownBy(() -> service.submit(ScopeType.ORGANIZATION, ORG_ID, CAMPAIGN_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(AdvertisingErrorCode.AD_027));

            stubCampaignLookup(campaign(CampaignStatus.DRAFT));
            assertThatThrownBy(() -> service.approve(CAMPAIGN_ID, ADMIN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(AdvertisingErrorCode.AD_027));

            assertThatThrownBy(() -> service.pause(ScopeType.ORGANIZATION, ORG_ID, CAMPAIGN_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(AdvertisingErrorCode.AD_027));
        }

        @Test
        @DisplayName("ac1_5: ACTIVE 中の update（PUT）→ AD_027")
        void ac1_5_ACTIVE中のupdateはAD_027() {
            stubCampaignLookup(campaign(CampaignStatus.ACTIVE));

            assertThatThrownBy(() -> service.update(
                    ScopeType.ORGANIZATION, ORG_ID, CAMPAIGN_ID, validRequest(RATE_CARD_ID)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(AdvertisingErrorCode.AD_027));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-1.4 編集可否マトリクスと snapshot 再確定
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-1.4 編集可否と snapshot 再確定規則")
    class Ac1_4_EditMatrix {

        @Test
        @DisplayName("ac1_4: DRAFT の rateCardId 変更 → snapshot が新カード単価で再確定")
        void ac1_4_DRAFTのrateCardId変更でsnapshot再確定() {
            stubCampaignLookup(campaign(CampaignStatus.DRAFT));

            OperationalCampaignResponse res = service.update(
                    ScopeType.ORGANIZATION, ORG_ID, CAMPAIGN_ID, validRequest(RATE_CARD_2_ID));

            assertThat(res.rateCardId()).isEqualTo(RATE_CARD_2_ID);
            assertThat(res.unitPriceSnapshot()).isEqualByComparingTo(UNIT_PRICE_2);
        }

        @Test
        @DisplayName("ac1_4: PAUSED の name/dailyBudget/endDate 変更 → 成功かつ snapshot 不変")
        void ac1_4_PAUSEDの許可フィールド変更はsnapshot不変() {
            stubCampaignLookup(campaign(CampaignStatus.PAUSED));

            CreateOperationalCampaignRequest req = new CreateOperationalCampaignRequest(
                    "改名後", PricingModel.CPM, new BigDecimal("2000"),
                    TODAY.plusDays(1), TODAY.plusDays(60), RATE_CARD_ID);
            OperationalCampaignResponse res = service.update(
                    ScopeType.ORGANIZATION, ORG_ID, CAMPAIGN_ID, req);

            assertThat(res.name()).isEqualTo("改名後");
            assertThat(res.unitPriceSnapshot())
                    .as("PAUSED では snapshot 不変（resume 後の課金単価保証）")
                    .isEqualByComparingTo(UNIT_PRICE);
        }

        @Test
        @DisplayName("ac1_4: PAUSED の rateCardId 変更 → AD_027（編集不可フィールド）")
        void ac1_4_PAUSEDのrateCardId変更はAD_027() {
            stubCampaignLookup(campaign(CampaignStatus.PAUSED));

            assertThatThrownBy(() -> service.update(
                    ScopeType.ORGANIZATION, ORG_ID, CAMPAIGN_ID, validRequest(RATE_CARD_2_ID)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(AdvertisingErrorCode.AD_027));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-1.5 / AC-1.10 バリデーション規則と境界
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-1.5 / AC-1.10 バリデーション規則と境界")
    class Ac1_5_10_Validation {

        @Test
        @DisplayName("ac1_5: dailyBudget < min_daily_budget → AD_028")
        void ac1_5_日予算不足はAD_028() {
            CreateOperationalCampaignRequest req = new CreateOperationalCampaignRequest(
                    "予算不足", PricingModel.CPM, MIN_DAILY_BUDGET.subtract(BigDecimal.ONE),
                    TODAY.plusDays(1), TODAY.plusDays(30), RATE_CARD_ID);

            assertThatThrownBy(() -> service.create(ScopeType.ORGANIZATION, ORG_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(AdvertisingErrorCode.AD_028));
        }

        @Test
        @DisplayName("ac1_5: 申込日が rate_card の effective 期間外 → AD_031")
        void ac1_5_期間外カードはAD_031() {
            Long expiredCardId = 99L;
            AdRateCardEntity expired = AdRateCardEntity.builder()
                    .id(expiredCardId)
                    .pricingModel(PricingModel.CPM)
                    .unitPrice(UNIT_PRICE)
                    .minDailyBudget(MIN_DAILY_BUDGET)
                    .effectiveFrom(TODAY.minusDays(60))
                    .effectiveUntil(TODAY.minusDays(10))
                    .createdBy(ADMIN_USER_ID)
                    .build();
            given(adRateCardRepository.findById(expiredCardId)).willReturn(Optional.of(expired));

            assertThatThrownBy(() -> service.create(
                    ScopeType.ORGANIZATION, ORG_ID, USER_ID, validRequest(expiredCardId)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(AdvertisingErrorCode.AD_031));
        }

        @Test
        @DisplayName("ac1_5: pricingModel が rate_card と不一致 → AD_031")
        void ac1_5_pricingModel不一致はAD_031() {
            CreateOperationalCampaignRequest req = new CreateOperationalCampaignRequest(
                    "モデル不一致", PricingModel.CPC, MIN_DAILY_BUDGET,
                    TODAY.plusDays(1), TODAY.plusDays(30), RATE_CARD_ID); // RATE_CARD_ID は CPM カード

            assertThatThrownBy(() -> service.create(ScopeType.ORGANIZATION, ORG_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(AdvertisingErrorCode.AD_031));
        }

        @Test
        @DisplayName("ac1_5: startDate > endDate → AD_030")
        void ac1_5_期間逆転はAD_030() {
            CreateOperationalCampaignRequest req = new CreateOperationalCampaignRequest(
                    "期間逆転", PricingModel.CPM, MIN_DAILY_BUDGET,
                    TODAY.plusDays(30), TODAY.plusDays(1), RATE_CARD_ID);

            assertThatThrownBy(() -> service.create(ScopeType.ORGANIZATION, ORG_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(AdvertisingErrorCode.AD_030));
        }

        @Test
        @DisplayName("ac1_10: startDate = endDate（1 日キャンペーン）は成功")
        void ac1_10_同日開始終了は成功() {
            LocalDate day = TODAY.plusDays(7);
            CreateOperationalCampaignRequest req = new CreateOperationalCampaignRequest(
                    "1日", PricingModel.CPM, MIN_DAILY_BUDGET, day, day, RATE_CARD_ID);

            OperationalCampaignResponse res = service.create(ScopeType.ORGANIZATION, ORG_ID, USER_ID, req);

            assertThat(res.startDate()).isEqualTo(day);
            assertThat(res.endDate()).isEqualTo(day);
        }

        @Test
        @DisplayName("ac1_10: dailyBudget = min_daily_budget ちょうどは成功")
        void ac1_10_日予算が最低ちょうどは成功() {
            CreateOperationalCampaignRequest req = new CreateOperationalCampaignRequest(
                    "境界予算", PricingModel.CPM, MIN_DAILY_BUDGET,
                    TODAY.plusDays(1), TODAY.plusDays(30), RATE_CARD_ID);

            OperationalCampaignResponse res = service.create(ScopeType.ORGANIZATION, ORG_ID, USER_ID, req);

            assertThat(res.dailyBudget()).isEqualByComparingTo(MIN_DAILY_BUDGET);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-1.11 reject 理由の永続と再 submit クリア
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ac1_11: reject で rejectReason が設定され DRAFT に戻る。再 submit で rejectReason が null")
    void ac1_11_rejectReasonの設定とクリア() {
        stubCampaignLookup(campaign(CampaignStatus.PENDING_REVIEW));
        OperationalCampaignResponse rejected = service.reject(CAMPAIGN_ID, ADMIN_USER_ID, "素材不備");

        assertThat(rejected.status()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(rejected.rejectReason()).isEqualTo("素材不備");

        // 差戻し済み（rejectReason あり）の DRAFT を再 submit
        AdCampaignEntity rejectedEntity = AdCampaignEntity.builder()
                .id(CAMPAIGN_ID)
                .advertiserAccountId(ACCOUNT_ID)
                .name("差戻し済み")
                .status(CampaignStatus.DRAFT)
                .pricingModel(PricingModel.CPM)
                .dailyBudget(MIN_DAILY_BUDGET)
                .startDate(TODAY.plusDays(1))
                .endDate(TODAY.plusDays(30))
                .rateCardId(RATE_CARD_ID)
                .unitPriceSnapshot(UNIT_PRICE)
                .rejectReason("素材不備")
                .build();
        stubCampaignLookup(rejectedEntity);

        OperationalCampaignResponse resubmitted = service.submit(
                ScopeType.ORGANIZATION, ORG_ID, CAMPAIGN_ID, USER_ID);

        assertThat(resubmitted.status()).isEqualTo(CampaignStatus.PENDING_REVIEW);
        assertThat(resubmitted.rejectReason()).as("再 submit で NULL クリア").isNull();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-1b.2 / AC-1b.3 審査詳細（F09.19.1b 契約補完）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-1b 審査詳細（広告主帰属 + クリエイティブ）")
    class Ac1b_ReviewDetail {

        @Test
        @DisplayName("ac1b_2: 審査詳細が広告主帰属（advertiserName/scope）とクリエイティブ一覧を組み立てて返す")
        void ac1b_2_審査詳細が広告主帰属とクリエイティブを返す() {
            stubCampaignLookup(campaign(CampaignStatus.PENDING_REVIEW));
            given(advertiserAccountRepository.findById(ACCOUNT_ID))
                    .willReturn(Optional.of(namedAccount("株式会社テスト広告")));
            given(adEntityRepository.findByCampaignId(CAMPAIGN_ID)).willReturn(List.of(
                    creative(11L, "素材A", "IN_FEED"),
                    creative(12L, "素材B", "SIDEBAR_RIGHT")));

            OperationalCampaignReviewDetailResponse detail = service.getReviewDetail(CAMPAIGN_ID);

            assertThat(detail.id()).isEqualTo(CAMPAIGN_ID);
            assertThat(detail.advertiserAccountId()).isEqualTo(ACCOUNT_ID);
            assertThat(detail.advertiserName()).isEqualTo("株式会社テスト広告");
            assertThat(detail.scopeType()).isEqualTo(ScopeType.ORGANIZATION);
            assertThat(detail.scopeId()).isEqualTo(ORG_ID);
            assertThat(detail.creatives()).hasSize(2);
            assertThat(detail.creatives()).extracting(AdCreativeResponse::id)
                    .containsExactly(11L, 12L);
            assertThat(detail.creatives().get(0).title()).isEqualTo("素材A");
            assertThat(detail.creatives().get(0).status()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("ac1b_2: クリエイティブ 0 件でも creatives は空配列（null ではない）")
        void ac1b_2_クリエイティブゼロは空配列() {
            stubCampaignLookup(campaign(CampaignStatus.PENDING_REVIEW));
            given(advertiserAccountRepository.findById(ACCOUNT_ID))
                    .willReturn(Optional.of(namedAccount("広告主")));
            given(adEntityRepository.findByCampaignId(CAMPAIGN_ID)).willReturn(List.of());

            OperationalCampaignReviewDetailResponse detail = service.getReviewDetail(CAMPAIGN_ID);

            assertThat(detail.creatives()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("ac1b_3: 存在しないキャンペーン id の審査詳細 → AD_021（404 に解決）")
        void ac1b_3_存在しないidはAD_021() {
            given(adCampaignRepository.findById(CAMPAIGN_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getReviewDetail(CAMPAIGN_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(AdvertisingErrorCode.AD_021));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    /** company_name つきの広告主アカウント（審査詳細の advertiserName 検証用）。 */
    private AdvertiserAccountEntity namedAccount(String companyName) {
        return AdvertiserAccountEntity.builder()
                .id(ACCOUNT_ID)
                .scopeType(ScopeType.ORGANIZATION)
                .scopeId(ORG_ID)
                .companyName(companyName)
                .build();
    }

    /** クリエイティブ（ads）エンティティ。status は ACTIVE 固定（レスポンスの name() 変換確認用）。 */
    private com.mannschaft.app.advertising.entity.AdEntity creative(Long id, String title, String placement) {
        return com.mannschaft.app.advertising.entity.AdEntity.builder()
                .id(id)
                .campaignId(CAMPAIGN_ID)
                .title(title)
                .imageUrl("https://example.com/" + id + ".png")
                .destinationUrl("https://landing.example.com/" + id)
                .status(com.mannschaft.app.advertising.entity.AdEntity.AdStatus.ACTIVE)
                .placement(com.mannschaft.app.advertising.AdPlacement.valueOf(placement))
                .build();
    }

    /** 実装がどの取得経路（findById / findByIdAnd…）を採っても効くよう findById を基本線でスタブする。 */
    private void stubCampaignLookup(AdCampaignEntity entity) {
        given(adCampaignRepository.findById(CAMPAIGN_ID)).willReturn(Optional.of(entity));
    }

    private CreateOperationalCampaignRequest validRequest(Long rateCardId) {
        return new CreateOperationalCampaignRequest(
                "夏季キャンペーン", PricingModel.CPM, new BigDecimal("3000"),
                TODAY.plusDays(1), TODAY.plusDays(30), rateCardId);
    }

    private AdCampaignEntity campaign(CampaignStatus status) {
        return AdCampaignEntity.builder()
                .id(CAMPAIGN_ID)
                .advertiserAccountId(ACCOUNT_ID)
                .name("夏季キャンペーン")
                .status(status)
                .pricingModel(PricingModel.CPM)
                .dailyBudget(new BigDecimal("3000"))
                .startDate(TODAY.plusDays(1))
                .endDate(TODAY.plusDays(30))
                .rateCardId(RATE_CARD_ID)
                .unitPriceSnapshot(UNIT_PRICE)
                .build();
    }

    private AdRateCardEntity rateCard(Long id, BigDecimal unitPrice) {
        return AdRateCardEntity.builder()
                .id(id)
                .pricingModel(PricingModel.CPM)
                .unitPrice(unitPrice)
                .minDailyBudget(MIN_DAILY_BUDGET)
                .effectiveFrom(TODAY.minusDays(30))
                .effectiveUntil(null)
                .createdBy(ADMIN_USER_ID)
                .build();
    }
}
