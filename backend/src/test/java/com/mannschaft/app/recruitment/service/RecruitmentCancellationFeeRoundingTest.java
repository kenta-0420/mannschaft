package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.recruitment.CancellationFeeType;
import com.mannschaft.app.recruitment.RecruitmentMapper;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.dto.CancellationFeeEstimateResponse;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationPolicyEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationPolicyTierEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationPolicyRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationPolicyTierRepository;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.BDDMockito.given;

/**
 * F03.11.1 キャンセル料の丸め（設計書 §6.1・R-1 御裁可）の試練。
 *
 * <p>受け入れ条件 AC-24（固定額が参加費を超える設定でも参加費で丸められる）・
 * AC-25（試算 API の見積り額と実徴収額が一致する）・AC-14（無料境界の既存挙動の固定）を担う。</p>
 *
 * <p>丸めは {@code calculateFee()} の<b>出口に一度だけ</b>置く。分岐ごとに撒くと新しい
 * {@code CancellationFeeType} が増えたときに漏れ、見積りと徴収額の一致が崩れる（§6.1）。</p>
 *
 * <p>本クラスは実装より前に書かれた red テストである。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("F03.11.1 キャンセル料の丸め 試練")
class RecruitmentCancellationFeeRoundingTest {

    @Mock private RecruitmentCancellationPolicyRepository policyRepository;
    @Mock private RecruitmentCancellationPolicyTierRepository tierRepository;
    @Mock private AccessControlService accessControlService;
    @Mock private RecruitmentMapper mapper;

    @InjectMocks private RecruitmentCancellationPolicyService service;

    private static final Long POLICY_ID = 100L;
    private static final Long LISTING_ID = 200L;
    private static final Long TEAM_ID = 10L;
    private static final Long USER_ID = 1L;

    private static final LocalDateTime START_AT = LocalDateTime.of(2026, 5, 1, 12, 0);

    @Test
    @DisplayName("AC-24: 固定額のキャンセル料が参加費を超える設定でも、徴収額は参加費と同額に丸められる")
    void ac24_fixedFeeAbovePrice_isCappedAtPrice() throws Exception {
        // 参加費 3,000 円・固定キャンセル料 5,000 円。Stripe は与信額超のキャプチャを受け付けない（§4.1-3）。
        RecruitmentListingEntity listing = paidListing(3_000);
        given(policyRepository.findById(POLICY_ID)).willReturn(Optional.of(policy(168)));
        given(tierRepository.findByPolicyIdOrderByTierOrderAsc(POLICY_ID))
                .willReturn(List.of(tier(1L, 1, 24, CancellationFeeType.FIXED, 5_000)));

        RecruitmentCancellationPolicyService.CalculatedFee fee =
                service.calculateFee(listing, START_AT.minusHours(10));

        assertThat(fee.feeAmount())
                .as("徴収額が与信額を超えることは構造的に起こらないようにする")
                .isEqualTo(3_000);
    }

    @Test
    @DisplayName("AC-25: 試算 API の見積り額と、実際に徴収される額（記録に入る額）が一致する")
    void ac25_estimateMatchesCalculatedFee() throws Exception {
        // 丸めが起きる条件（FIXED > price）で必ず 1 ケース起こす。
        RecruitmentListingEntity listing = paidListing(3_000);
        given(policyRepository.findById(POLICY_ID)).willReturn(Optional.of(policy(168)));
        given(tierRepository.findByPolicyIdOrderByTierOrderAsc(POLICY_ID))
                .willReturn(List.of(tier(1L, 1, 24, CancellationFeeType.FIXED, 5_000)));

        LocalDateTime cancelAt = START_AT.minusHours(10);
        CancellationFeeEstimateResponse estimate = service.estimateFee(listing, cancelAt);
        RecruitmentCancellationPolicyService.CalculatedFee actual = service.calculateFee(listing, cancelAt);

        // 「見積りより多く取られた」という事態を構造的に起こさせない（§6.1）。
        assertThat(estimate.getFeeAmount()).isEqualTo(actual.feeAmount());
        assertThat(estimate.getFeeAmount()).isEqualTo(3_000);
    }

    @Test
    @DisplayName("AC-24(境界): PERCENTAGE=100 でも丸めの着地点は参加費と同額（AC-13 と同じ状態へ寄る）")
    void ac24_percentageHundred_landsOnPrice() throws Exception {
        RecruitmentListingEntity listing = paidListing(3_000);
        given(policyRepository.findById(POLICY_ID)).willReturn(Optional.of(policy(168)));
        given(tierRepository.findByPolicyIdOrderByTierOrderAsc(POLICY_ID))
                .willReturn(List.of(tier(1L, 1, 24, CancellationFeeType.PERCENTAGE, 100)));

        RecruitmentCancellationPolicyService.CalculatedFee fee =
                service.calculateFee(listing, START_AT.minusHours(10));

        assertThat(fee.feeAmount()).isEqualTo(3_000);
    }

    @Test
    @DisplayName("AC-24(境界): 参加費が未設定なら丸めの基準が無く、算出結果をそのまま保つ")
    void ac24_nullPrice_isNotRounded() throws Exception {
        // 参加費が無ければ与信も立たず徴収対象になりえない（§6.1）。
        RecruitmentListingEntity listing = paidListing(null);
        given(policyRepository.findById(POLICY_ID)).willReturn(Optional.of(policy(168)));
        given(tierRepository.findByPolicyIdOrderByTierOrderAsc(POLICY_ID))
                .willReturn(List.of(tier(1L, 1, 24, CancellationFeeType.FIXED, 5_000)));

        RecruitmentCancellationPolicyService.CalculatedFee fee =
                service.calculateFee(listing, START_AT.minusHours(10));

        assertThat(fee.feeAmount()).isEqualTo(5_000);
    }

    @Test
    @DisplayName("AC-14: hoursBefore == freeUntilHoursBefore は無料（既存挙動の固定・丸めの追加で壊れないこと）")
    void ac14_equalToFreeBoundaryStaysFree() throws Exception {
        RecruitmentListingEntity listing = paidListing(3_000);
        given(policyRepository.findById(POLICY_ID)).willReturn(Optional.of(policy(168)));

        RecruitmentCancellationPolicyService.CalculatedFee fee =
                service.calculateFee(listing, START_AT.minusHours(168));

        // 等号は無料側に倒れる（利用者に有利）。
        assertThat(fee.feeAmount()).isZero();
        assertThat(fee.freeUntilApplied()).isTrue();
    }

    // ==========================================================
    // ヘルパー
    // ==========================================================

    private RecruitmentListingEntity paidListing(Integer price) throws Exception {
        RecruitmentListingEntity listing = RecruitmentListingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(TEAM_ID)
                .categoryId(1L)
                .title("試練用の札")
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(START_AT)
                .endAt(START_AT.plusHours(2))
                .applicationDeadline(START_AT.minusDays(1))
                .autoCancelAt(START_AT.minusDays(1))
                .capacity(10)
                .minCapacity(1)
                .paymentEnabled(true)
                .price(price)
                .visibility(RecruitmentVisibility.SCOPE_ONLY)
                .createdBy(USER_ID)
                .cancellationPolicyId(POLICY_ID)
                .build();
        setId(listing, LISTING_ID);
        return listing;
    }

    private RecruitmentCancellationPolicyEntity policy(int freeUntilHours) throws Exception {
        RecruitmentCancellationPolicyEntity policy = RecruitmentCancellationPolicyEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(TEAM_ID)
                .freeUntilHoursBefore(freeUntilHours)
                .isTemplatePolicy(false)
                .createdBy(USER_ID)
                .build();
        setId(policy, POLICY_ID);
        return policy;
    }

    private RecruitmentCancellationPolicyTierEntity tier(
            Long id, int tierOrder, int hoursBefore, CancellationFeeType feeType, int feeValue) throws Exception {
        RecruitmentCancellationPolicyTierEntity tier = RecruitmentCancellationPolicyTierEntity.builder()
                .policyId(POLICY_ID)
                .tierOrder(tierOrder)
                .appliesAtOrBeforeHours(hoursBefore)
                .feeType(feeType)
                .feeValue(feeValue)
                .build();
        setId(tier, id);
        return tier;
    }

    private void setId(Object entity, Long id) throws Exception {
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
