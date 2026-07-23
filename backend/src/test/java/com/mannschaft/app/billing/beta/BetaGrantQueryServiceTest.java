package com.mannschaft.app.billing.beta;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.billing.EntitlementRepository;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.beta.dto.MyBetaPerksResponse;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

/**
 * F20.3 {@link BetaGrantQueryService} の /me eligibility 挙動 単体試練（AC-N4）。
 *
 * <p>現行フェーズの criteria が未定義（{@link BetaPerkErrorCode#CRITERIA_NOT_FOUND}）のとき、eligibility は
 * <b>null で返る</b>（例外を握り潰す症状隠蔽ではなく、設計仕様に基づく唯一の例外的 catch・NPE/404 にしない）。
 * それ以外の {@link BusinessException} は素通しで伝播する（対処療法にしない）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("F20.3 BetaGrantQueryService /me eligibility 試練")
class BetaGrantQueryServiceTest {

    @Mock
    private BetaGrantRepository betaGrantRepository;
    @Mock
    private EntitlementRepository entitlementRepository;
    @Mock
    private BetaPerkEligibilityService eligibilityService;
    @Spy
    private BetaGrantResponseMapper mapper = new BetaGrantResponseMapper(new ObjectMapper());

    @InjectMocks
    private BetaGrantQueryService queryService;

    private static final long USER_ID = 9L;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(queryService, "currentPhase", 2);
        given(betaGrantRepository.findByScopeKindAndScopeIdOrderByGrantedAtDesc(
                EntitlementScopeKind.USER, USER_ID)).willReturn(List.of());
    }

    @Test
    @DisplayName("AC-N4: criteria 未定義（CRITERIA_NOT_FOUND）のとき eligibility は null（404/NPE にしない）")
    void getMyBetaPerks_criteriaNotFound_eligibilityNull() {
        willThrow(new BusinessException(BetaPerkErrorCode.CRITERIA_NOT_FOUND))
                .given(eligibilityService).evaluate(
                        eq(GrantKind.INDIVIDUAL), eq(EntitlementScopeKind.USER), eq(USER_ID), anyInt());

        MyBetaPerksResponse res = queryService.getMyBetaPerks(USER_ID);

        assertThat(res.getEligibility()).isNull();
        assertThat(res.getGrants()).isEmpty();
    }

    @Test
    @DisplayName("AC-N4: criteria 定義済みなら eligibility を返す")
    void getMyBetaPerks_criteriaDefined_eligibilityPresent() {
        given(eligibilityService.evaluate(
                eq(GrantKind.INDIVIDUAL), eq(EntitlementScopeKind.USER), eq(USER_ID), anyInt()))
                .willReturn(new EligibilityResult(false,
                        List.of(new MetricProgress("activeDays", 9, 14)), 2, 30));

        MyBetaPerksResponse res = queryService.getMyBetaPerks(USER_ID);

        assertThat(res.getEligibility()).isNotNull();
        assertThat(res.getEligibility().getBetaPhase()).isEqualTo(2);
        assertThat(res.getEligibility().isEligible()).isFalse();
        assertThat(res.getEligibility().getMetrics()).hasSize(1);
    }

    @Test
    @DisplayName("AC-N4: CRITERIA_NOT_FOUND 以外の BusinessException は伝播する（握り潰さない）")
    void getMyBetaPerks_otherBusinessException_propagates() {
        willThrow(new BusinessException(BetaPerkErrorCode.BETA_PHASE_INVALID))
                .given(eligibilityService).evaluate(any(), any(), any(), anyInt());

        assertThatThrownBy(() -> queryService.getMyBetaPerks(USER_ID))
                .isInstanceOf(BusinessException.class);
    }
}
