package com.mannschaft.app.billing.beta;

import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.ScopeMemberCountService;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * {@link BetaPerkEligibilityService} 単体テスト（F20.3 付与判定・試練先行）。
 *
 * <p>受け入れ条件: AC-N1（activeDays=0→false）・AC-N4（criteria 未定義/disabled→CRITERIA_NOT_FOUND・NPE にしない）・
 * AC-B1（actual==required→true）・AC-B2（actual==required-1→false）。「非 NULL 指標の AND・境界は以上」を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BetaPerkEligibilityService 単体テスト（付与条件の活動実績評価）")
class BetaPerkEligibilityServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC);

    @Mock private BetaPerkCriteriaRepository criteriaRepository;
    @Mock private LoginActivityQueryService loginActivityQueryService;
    @Mock private MembershipQueryService membershipQueryService;
    @Mock private ScopeMemberCountService scopeMemberCountService;

    private BetaPerkEligibilityService service;

    @BeforeEach
    void setUp() {
        service = new BetaPerkEligibilityService(
                criteriaRepository, loginActivityQueryService, membershipQueryService,
                scopeMemberCountService, FIXED_CLOCK);
    }

    private BetaPerkCriteriaEntity criteria(
            GrantKind kind, Integer minActiveDays, Integer minTenure, Integer minMembers, boolean enabled) {
        return BetaPerkCriteriaEntity.builder()
                .betaPhase(1)
                .grantKind(kind)
                .evaluationWindowDays(60)
                .minActiveDays(minActiveDays)
                .minMembershipTenureDays(minTenure)
                .minActiveMembers(minMembers)
                .enabled(enabled)
                .build();
    }

    @Test
    @DisplayName("AC-N1: activeDays=0 は eligible=false（activeDays 指標が未達）")
    void acN1_activeDaysZero_notEligible() {
        given(criteriaRepository.findById(new BetaPerkCriteriaId(1, GrantKind.INDIVIDUAL)))
                .willReturn(Optional.of(criteria(GrantKind.INDIVIDUAL, 14, null, null, true)));
        given(loginActivityQueryService.countDistinctActiveDays(eq(42L), any())).willReturn(0L);

        EligibilityResult result = service.evaluate(
                GrantKind.INDIVIDUAL, EntitlementScopeKind.USER, 42L, 1);

        assertThat(result.eligible()).isFalse();
        assertThat(result.metrics()).singleElement().satisfies(m -> {
            assertThat(m.metricKey()).isEqualTo("activeDays");
            assertThat(m.actual()).isZero();
            assertThat(m.required()).isEqualTo(14L);
        });
    }

    @Test
    @DisplayName("AC-N4: criteria 未定義は CRITERIA_NOT_FOUND(404)（NPE にしない）")
    void acN4_criteriaMissing_throwsCriteriaNotFound() {
        given(criteriaRepository.findById(new BetaPerkCriteriaId(1, GrantKind.INDIVIDUAL)))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.evaluate(GrantKind.INDIVIDUAL, EntitlementScopeKind.USER, 42L, 1))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(BetaPerkErrorCode.CRITERIA_NOT_FOUND);
    }

    @Test
    @DisplayName("AC-N4(補): enabled=false も CRITERIA_NOT_FOUND（付与停止中）")
    void acN4_criteriaDisabled_throwsCriteriaNotFound() {
        given(criteriaRepository.findById(new BetaPerkCriteriaId(1, GrantKind.INDIVIDUAL)))
                .willReturn(Optional.of(criteria(GrantKind.INDIVIDUAL, null, 30, null, false)));

        assertThatThrownBy(() -> service.evaluate(GrantKind.INDIVIDUAL, EntitlementScopeKind.USER, 42L, 1))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(BetaPerkErrorCode.CRITERIA_NOT_FOUND);
    }

    @Test
    @DisplayName("AC-B1: actual==required は eligible=true（境界は以上）")
    void acB1_actualEqualsRequired_eligible() {
        given(criteriaRepository.findById(new BetaPerkCriteriaId(1, GrantKind.INDIVIDUAL)))
                .willReturn(Optional.of(criteria(GrantKind.INDIVIDUAL, null, 30, null, true)));
        given(membershipQueryService.tenureDays(eq(EntitlementScopeKind.USER), eq(42L), any()))
                .willReturn(30L);

        EligibilityResult result = service.evaluate(
                GrantKind.INDIVIDUAL, EntitlementScopeKind.USER, 42L, 1);

        assertThat(result.eligible()).isTrue();
        assertThat(result.metrics()).singleElement().satisfies(m -> {
            assertThat(m.metricKey()).isEqualTo("membershipTenureDays");
            assertThat(m.actual()).isEqualTo(30L);
            assertThat(m.required()).isEqualTo(30L);
        });
    }

    @Test
    @DisplayName("AC-B2: actual==required-1 は eligible=false")
    void acB2_actualOneBelow_notEligible() {
        given(criteriaRepository.findById(new BetaPerkCriteriaId(1, GrantKind.INDIVIDUAL)))
                .willReturn(Optional.of(criteria(GrantKind.INDIVIDUAL, null, 30, null, true)));
        given(membershipQueryService.tenureDays(eq(EntitlementScopeKind.USER), eq(42L), any()))
                .willReturn(29L);

        EligibilityResult result = service.evaluate(
                GrantKind.INDIVIDUAL, EntitlementScopeKind.USER, 42L, 1);

        assertThat(result.eligible()).isFalse();
    }

    @Test
    @DisplayName("TEAM_ORG: 複数指標(tenure/activeMembers)の AND。全達成で true（activeDays は計測しない）")
    void teamOrg_multipleMetrics_andTrue() {
        given(criteriaRepository.findById(new BetaPerkCriteriaId(2, GrantKind.TEAM_ORG)))
                .willReturn(Optional.of(BetaPerkCriteriaEntity.builder()
                        .betaPhase(2).grantKind(GrantKind.TEAM_ORG).evaluationWindowDays(60)
                        .minActiveDays(14).minMembershipTenureDays(30).minActiveMembers(5).enabled(true).build()));
        given(membershipQueryService.tenureDays(eq(EntitlementScopeKind.TEAM), eq(7L), any())).willReturn(40L);
        given(scopeMemberCountService.countActiveMembers(EntitlementScopeKind.TEAM, 7L)).willReturn(6);

        EligibilityResult result = service.evaluate(GrantKind.TEAM_ORG, EntitlementScopeKind.TEAM, 7L, 2);

        assertThat(result.eligible()).isTrue();
        // activeDays は TEAM_ORG では計測しない（USER 非対応・README §7）→ 2 指標のみ。
        assertThat(result.metrics()).extracting(MetricProgress::metricKey)
                .containsExactly("membershipTenureDays", "activeMembers");
    }
}
