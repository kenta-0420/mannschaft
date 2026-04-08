package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.recruitment.CancellationFeeType;
import com.mannschaft.app.recruitment.RecruitmentErrorCode;
import com.mannschaft.app.recruitment.RecruitmentMapper;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.dto.CancellationPolicyTierRequest;
import com.mannschaft.app.recruitment.dto.CreateCancellationPolicyRequest;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationPolicyEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationPolicyTierEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationPolicyRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationPolicyTierRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * {@link RecruitmentCancellationPolicyService} の単体テスト。
 * §5.9 キャンセル料計算ロジックの境界値検証を含む。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecruitmentCancellationPolicyService 単体テスト")
class RecruitmentCancellationPolicyServiceTest {

    @Mock
    private RecruitmentCancellationPolicyRepository policyRepository;

    @Mock
    private RecruitmentCancellationPolicyTierRepository tierRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private RecruitmentMapper mapper;

    @InjectMocks
    private RecruitmentCancellationPolicyService service;

    private static final Long POLICY_ID = 100L;
    private static final Long LISTING_ID = 200L;
    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;

    // ========================================
    // §5.9 キャンセル料計算 - 境界値テスト (3ケース必須)
    // ========================================

    @Nested
    @DisplayName("§5.9 calculateFee - 境界値")
    class CalculateFeeBoundary {

        /**
         * 境界値1: hoursBefore == free_until_hours_before の等号ケース。
         * 設計書 §5.9 の重要仕様: 等号は無料側に倒れる (ユーザー有利)。
         */
        @Test
        @DisplayName("境界値: hoursBefore == free_until_hours_before → 無料 (等号含む)")
        void calculateFee_boundaryFreeUntilEqual_returnsFree() throws Exception {
            // Arrange: free_until=168h, キャンセル時刻が開催の168時間前ジャスト
            LocalDateTime startAt = LocalDateTime.of(2026, 5, 1, 12, 0);
            LocalDateTime cancelAt = startAt.minusHours(168); // 168時間ぴったり前

            RecruitmentListingEntity listing = buildPaymentListing(POLICY_ID, 5000);
            RecruitmentCancellationPolicyEntity policy = buildPolicy(168);

            given(policyRepository.findById(POLICY_ID)).willReturn(Optional.of(policy));
            // 注: 等号で無料判定されるため tierRepository は呼ばれない (stub 不要)

            // Act
            RecruitmentCancellationPolicyService.CalculatedFee fee = service.calculateFee(listing, cancelAt);

            // Assert: 等号 → 無料
            assertThat(fee.feeAmount()).isEqualTo(0);
            assertThat(fee.freeUntilApplied()).isTrue();
            assertThat(fee.tierId()).isNull();
        }

        /**
         * 境界値2: tier の applies_at_or_before_hours と一致するケース。
         * 設計書 §5.9 の例: 50時間前 → tier1(168h)とtier2(72h)を満たす → tier_order 大きい tier2 が選択される
         */
        @Test
        @DisplayName("境界値: hoursBefore < free_until のとき、複数tier該当 → tier_order 最大が選択される")
        void calculateFee_multipleTiersMatch_largestTierOrderSelected() throws Exception {
            LocalDateTime startAt = LocalDateTime.of(2026, 5, 1, 12, 0);
            LocalDateTime cancelAt = startAt.minusHours(50); // 50時間前

            RecruitmentListingEntity listing = buildPaymentListing(POLICY_ID, 5000);
            RecruitmentCancellationPolicyEntity policy = buildPolicy(168);

            given(policyRepository.findById(POLICY_ID)).willReturn(Optional.of(policy));
            given(tierRepository.findByPolicyIdOrderByTierOrderAsc(POLICY_ID))
                    .willReturn(List.of(
                            buildTier(1L, 1, 168, CancellationFeeType.PERCENTAGE, 30),
                            buildTier(2L, 2, 72, CancellationFeeType.PERCENTAGE, 50),
                            buildTier(3L, 3, 24, CancellationFeeType.PERCENTAGE, 80)));

            // Act
            RecruitmentCancellationPolicyService.CalculatedFee fee = service.calculateFee(listing, cancelAt);

            // Assert: 50 <= 168, 50 <= 72, 50 > 24 → tier1 と tier2 該当、tier2 (50%) が選択される
            assertThat(fee.feeAmount()).isEqualTo(2500); // 5000 × 50% = 2500
            assertThat(fee.tierOrder()).isEqualTo(2);
            assertThat(fee.freeUntilApplied()).isFalse();
        }

        /**
         * 境界値3: hoursBefore = 0 (開催直前) または負数 (開催後)。
         * 全 tier が条件を満たす → tier_order 最大 (最も直前) を選択。
         */
        @Test
        @DisplayName("境界値: hoursBefore = 0 (開催直前) → 全tier該当、最も直前のtier (tier_order最大) が適用")
        void calculateFee_atStartTime_appliesLatestTier() throws Exception {
            LocalDateTime startAt = LocalDateTime.of(2026, 5, 1, 12, 0);
            LocalDateTime cancelAt = startAt; // 開催時刻ジャスト

            RecruitmentListingEntity listing = buildPaymentListing(POLICY_ID, 5000);
            RecruitmentCancellationPolicyEntity policy = buildPolicy(168);

            given(policyRepository.findById(POLICY_ID)).willReturn(Optional.of(policy));
            given(tierRepository.findByPolicyIdOrderByTierOrderAsc(POLICY_ID))
                    .willReturn(List.of(
                            buildTier(1L, 1, 168, CancellationFeeType.PERCENTAGE, 30),
                            buildTier(2L, 2, 72, CancellationFeeType.PERCENTAGE, 50),
                            buildTier(3L, 3, 24, CancellationFeeType.PERCENTAGE, 80),
                            buildTier(4L, 4, 2, CancellationFeeType.PERCENTAGE, 100)));

            // Act
            RecruitmentCancellationPolicyService.CalculatedFee fee = service.calculateFee(listing, cancelAt);

            // Assert: 0 ≤ 全tierの境界、tier4 (100%) が選択される
            assertThat(fee.feeAmount()).isEqualTo(5000);
            assertThat(fee.tierOrder()).isEqualTo(4);
        }

        @Test
        @DisplayName("payment_enabled=false → 常に無料")
        void calculateFee_paymentDisabled_returnsFree() throws Exception {
            RecruitmentListingEntity listing = buildListingWithoutPayment(POLICY_ID);
            LocalDateTime cancelAt = LocalDateTime.now();

            RecruitmentCancellationPolicyService.CalculatedFee fee = service.calculateFee(listing, cancelAt);

            assertThat(fee.feeAmount()).isEqualTo(0);
            assertThat(fee.freeUntilApplied()).isTrue();
        }

        @Test
        @DisplayName("cancellation_policy_id = NULL → 無料")
        void calculateFee_nullPolicy_returnsFree() throws Exception {
            RecruitmentListingEntity listing = buildPaymentListing(null, 5000);
            LocalDateTime cancelAt = LocalDateTime.now();

            RecruitmentCancellationPolicyService.CalculatedFee fee = service.calculateFee(listing, cancelAt);

            assertThat(fee.feeAmount()).isEqualTo(0);
            assertThat(fee.freeUntilApplied()).isTrue();
        }

        @Test
        @DisplayName("FIXED tier → fee_value がそのまま fee_amount")
        void calculateFee_fixedTier_returnsAbsoluteAmount() throws Exception {
            LocalDateTime startAt = LocalDateTime.of(2026, 5, 1, 12, 0);
            LocalDateTime cancelAt = startAt.minusHours(10);

            RecruitmentListingEntity listing = buildPaymentListing(POLICY_ID, 5000);
            RecruitmentCancellationPolicyEntity policy = buildPolicy(168);

            given(policyRepository.findById(POLICY_ID)).willReturn(Optional.of(policy));
            given(tierRepository.findByPolicyIdOrderByTierOrderAsc(POLICY_ID))
                    .willReturn(List.of(
                            buildTier(1L, 1, 24, CancellationFeeType.FIXED, 1500)));

            RecruitmentCancellationPolicyService.CalculatedFee fee = service.calculateFee(listing, cancelAt);

            assertThat(fee.feeAmount()).isEqualTo(1500);
        }
    }

    // ========================================
    // createPolicy - tier validation
    // ========================================

    @Nested
    @DisplayName("createPolicy バリデーション")
    class CreatePolicyValidation {

        @Test
        @DisplayName("4段階を超える → RECRUITMENT_303")
        void createPolicy_moreThan4Tiers_throws303() {
            CreateCancellationPolicyRequest request = new CreateCancellationPolicyRequest(
                    "test", 168, false,
                    List.of(
                            new CancellationPolicyTierRequest(1, 168, CancellationFeeType.PERCENTAGE, 10),
                            new CancellationPolicyTierRequest(2, 100, CancellationFeeType.PERCENTAGE, 20),
                            new CancellationPolicyTierRequest(3, 50, CancellationFeeType.PERCENTAGE, 30),
                            new CancellationPolicyTierRequest(4, 24, CancellationFeeType.PERCENTAGE, 50),
                            new CancellationPolicyTierRequest(5, 2, CancellationFeeType.PERCENTAGE, 100)
                    ));

            assertThatThrownBy(() -> service.createPolicy(RecruitmentScopeType.TEAM, TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.TIER_LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("tier の applies_at_or_before_hours が free_until 以上 → INVALID")
        void createPolicy_tierExceedsFreeUntil_throwsInvalid() {
            CreateCancellationPolicyRequest request = new CreateCancellationPolicyRequest(
                    "test", 100, false,
                    List.of(new CancellationPolicyTierRequest(1, 200, CancellationFeeType.PERCENTAGE, 10)));

            assertThatThrownBy(() -> service.createPolicy(RecruitmentScopeType.TEAM, TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.INVALID_CANCELLATION_POLICY);
        }

        @Test
        @DisplayName("tier_order 重複 → INVALID")
        void createPolicy_duplicateTierOrder_throwsInvalid() {
            CreateCancellationPolicyRequest request = new CreateCancellationPolicyRequest(
                    "test", 200, false,
                    List.of(
                            new CancellationPolicyTierRequest(1, 100, CancellationFeeType.PERCENTAGE, 10),
                            new CancellationPolicyTierRequest(1, 50, CancellationFeeType.PERCENTAGE, 20)));

            assertThatThrownBy(() -> service.createPolicy(RecruitmentScopeType.TEAM, TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.INVALID_CANCELLATION_POLICY);
        }

        @Test
        @DisplayName("tier_order 大きいほど applies_at_or_before_hours は小さい必要、逆は TIER_RANGE_OVERLAP")
        void createPolicy_tierRangeNotDescending_throwsOverlap() {
            CreateCancellationPolicyRequest request = new CreateCancellationPolicyRequest(
                    "test", 200, false,
                    List.of(
                            new CancellationPolicyTierRequest(1, 50, CancellationFeeType.PERCENTAGE, 10),
                            new CancellationPolicyTierRequest(2, 100, CancellationFeeType.PERCENTAGE, 20))); // tier2 が tier1 より大きい (NG)

            assertThatThrownBy(() -> service.createPolicy(RecruitmentScopeType.TEAM, TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.TIER_RANGE_OVERLAP);
        }
    }

    // ========================================
    // ヘルパー
    // ========================================

    private RecruitmentListingEntity buildPaymentListing(Long policyId, Integer price) throws Exception {
        RecruitmentListingEntity listing = RecruitmentListingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(TEAM_ID)
                .categoryId(1L)
                .title("test")
                .participationType(com.mannschaft.app.recruitment.RecruitmentParticipationType.INDIVIDUAL)
                .startAt(LocalDateTime.of(2026, 5, 1, 12, 0))
                .endAt(LocalDateTime.of(2026, 5, 1, 14, 0))
                .applicationDeadline(LocalDateTime.of(2026, 4, 30, 23, 59))
                .autoCancelAt(LocalDateTime.of(2026, 4, 30, 23, 59))
                .capacity(10)
                .minCapacity(1)
                .paymentEnabled(true)
                .price(price)
                .visibility(com.mannschaft.app.recruitment.RecruitmentVisibility.SCOPE_ONLY)
                .createdBy(USER_ID)
                .cancellationPolicyId(policyId)
                .build();
        setIdField(listing, LISTING_ID);
        return listing;
    }

    private RecruitmentListingEntity buildListingWithoutPayment(Long policyId) throws Exception {
        RecruitmentListingEntity listing = RecruitmentListingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(TEAM_ID)
                .categoryId(1L)
                .title("test")
                .participationType(com.mannschaft.app.recruitment.RecruitmentParticipationType.INDIVIDUAL)
                .startAt(LocalDateTime.of(2026, 5, 1, 12, 0))
                .endAt(LocalDateTime.of(2026, 5, 1, 14, 0))
                .applicationDeadline(LocalDateTime.of(2026, 4, 30, 23, 59))
                .autoCancelAt(LocalDateTime.of(2026, 4, 30, 23, 59))
                .capacity(10)
                .minCapacity(1)
                .paymentEnabled(false)
                .visibility(com.mannschaft.app.recruitment.RecruitmentVisibility.SCOPE_ONLY)
                .createdBy(USER_ID)
                .cancellationPolicyId(policyId)
                .build();
        setIdField(listing, LISTING_ID);
        return listing;
    }

    private RecruitmentCancellationPolicyEntity buildPolicy(int freeUntilHours) throws Exception {
        RecruitmentCancellationPolicyEntity policy = RecruitmentCancellationPolicyEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(TEAM_ID)
                .freeUntilHoursBefore(freeUntilHours)
                .isTemplatePolicy(false)
                .createdBy(USER_ID)
                .build();
        setIdField(policy, POLICY_ID);
        return policy;
    }

    private RecruitmentCancellationPolicyTierEntity buildTier(
            Long id, int tierOrder, int hoursBefore, CancellationFeeType feeType, int feeValue) throws Exception {
        RecruitmentCancellationPolicyTierEntity tier = RecruitmentCancellationPolicyTierEntity.builder()
                .policyId(POLICY_ID)
                .tierOrder(tierOrder)
                .appliesAtOrBeforeHours(hoursBefore)
                .feeType(feeType)
                .feeValue(feeValue)
                .build();
        setIdField(tier, id);
        return tier;
    }

    /** BaseEntity の id は @GeneratedValue のためテストではリフレクションでセット。 */
    private void setIdField(Object entity, Long id) throws Exception {
        Class<?> clazz = entity.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField("id");
                f.setAccessible(true);
                f.set(entity, id);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException("id field not found");
    }
}
