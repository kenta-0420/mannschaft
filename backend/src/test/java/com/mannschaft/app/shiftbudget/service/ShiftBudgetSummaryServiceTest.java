package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.dto.ConsumptionSummaryResponse;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepository;
import com.mannschaft.app.shiftbudget.repository.TodoBudgetLinkRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * {@link ShiftBudgetSummaryService} 単体テスト（Phase 9-β / API #5）。
 *
 * <p>カバレッジ:</p>
 * <ul>
 *   <li>by_user/flags 形状の v1.2 確定ルール (Q2 御裁可: 常に空配列 + BY_USER_HIDDEN)</li>
 *   <li>status 4段階 (OK / WARN / EXCEEDED / SEVERE_EXCEEDED)</li>
 *   <li>境界値 (allocated=0 で割算回避)</li>
 *   <li>多テナント分離 (別組織 → ALLOCATION_NOT_FOUND)</li>
 *   <li>権限 (BUDGET_VIEW なし → 403)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftBudgetSummaryService 単体テスト")
class ShiftBudgetSummaryServiceTest {

    private static final Long USER_ID = 100L;
    private static final Long ORG_ID = 1L;
    private static final Long ALLOCATION_ID = 42L;

    @Mock
    private ShiftBudgetAllocationRepository allocationRepository;
    @Mock
    private ShiftBudgetConsumptionRepository consumptionRepository;
    @Mock
    private TodoBudgetLinkRepository todoBudgetLinkRepository;
    @Mock
    private ShiftBudgetFeatureService featureService;
    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private ShiftBudgetSummaryService service;

    @BeforeEach
    void setUpSecurity() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private void givenBudgetViewAllowed() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
    }

    private ShiftBudgetAllocationEntity entityWith(BigDecimal allocated, BigDecimal consumed,
                                                   BigDecimal confirmed) {
        return ShiftBudgetAllocationEntity.builder()
                .organizationId(ORG_ID)
                .teamId(12L)
                .fiscalYearId(3L)
                .budgetCategoryId(17L)
                .periodStart(LocalDate.of(2026, 6, 1))
                .periodEnd(LocalDate.of(2026, 6, 30))
                .allocatedAmount(allocated)
                .consumedAmount(consumed)
                .confirmedAmount(confirmed)
                .currency("JPY")
                .createdBy(USER_ID)
                .version(0L)
                .build();
    }

    @Nested
    @DisplayName("形状 (Q2 御裁可確認)")
    class Shape {

        @Test
        @DisplayName("Phase 9-β: by_user 常に空 + flags=BY_USER_HIDDEN を含む")
        void byUser常に空_flagsにBY_USER_HIDDEN() {
            givenBudgetViewAllowed();
            given(allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                    .willReturn(Optional.of(entityWith(
                            new BigDecimal("300000"),
                            new BigDecimal("245000"),
                            new BigDecimal("200000"))));

            ConsumptionSummaryResponse res = service.getConsumptionSummary(ORG_ID, ALLOCATION_ID);

            assertThat(res.byUser()).isNotNull().isEmpty();
            assertThat(res.flags()).contains(ShiftBudgetSummaryService.FLAG_BY_USER_HIDDEN);
            assertThat(res.alerts()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("基本数値: planned = consumed - confirmed, remaining = allocated - consumed")
        void 基本数値() {
            givenBudgetViewAllowed();
            given(allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                    .willReturn(Optional.of(entityWith(
                            new BigDecimal("300000"),
                            new BigDecimal("245000"),
                            new BigDecimal("200000"))));

            ConsumptionSummaryResponse res = service.getConsumptionSummary(ORG_ID, ALLOCATION_ID);

            assertThat(res.plannedAmount()).isEqualByComparingTo("45000");
            assertThat(res.remainingAmount()).isEqualByComparingTo("55000");
        }
    }

    @Nested
    @DisplayName("status 4段階判定")
    class StatusJudgement {

        @Test
        @DisplayName("rate < 0.80 → OK")
        void ok() {
            givenBudgetViewAllowed();
            given(allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                    .willReturn(Optional.of(entityWith(
                            new BigDecimal("100000"),
                            new BigDecimal("50000"),
                            BigDecimal.ZERO)));

            assertThat(service.getConsumptionSummary(ORG_ID, ALLOCATION_ID).status()).isEqualTo("OK");
        }

        @Test
        @DisplayName("0.80 ≤ rate < 1.00 → WARN")
        void warn() {
            givenBudgetViewAllowed();
            given(allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                    .willReturn(Optional.of(entityWith(
                            new BigDecimal("100000"),
                            new BigDecimal("85000"),
                            BigDecimal.ZERO)));

            assertThat(service.getConsumptionSummary(ORG_ID, ALLOCATION_ID).status()).isEqualTo("WARN");
        }

        @Test
        @DisplayName("1.00 ≤ rate < 1.20 → EXCEEDED")
        void exceeded() {
            givenBudgetViewAllowed();
            given(allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                    .willReturn(Optional.of(entityWith(
                            new BigDecimal("100000"),
                            new BigDecimal("110000"),
                            BigDecimal.ZERO)));

            assertThat(service.getConsumptionSummary(ORG_ID, ALLOCATION_ID).status()).isEqualTo("EXCEEDED");
        }

        @Test
        @DisplayName("rate ≥ 1.20 → SEVERE_EXCEEDED")
        void severe() {
            givenBudgetViewAllowed();
            given(allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                    .willReturn(Optional.of(entityWith(
                            new BigDecimal("100000"),
                            new BigDecimal("130000"),
                            BigDecimal.ZERO)));

            assertThat(service.getConsumptionSummary(ORG_ID, ALLOCATION_ID).status())
                    .isEqualTo("SEVERE_EXCEEDED");
        }

        @Test
        @DisplayName("allocated=0 / 境界 → OK + rate=0 (ゼロ除算回避)")
        void 予算ゼロ_OK() {
            givenBudgetViewAllowed();
            given(allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                    .willReturn(Optional.of(entityWith(BigDecimal.ZERO,
                            BigDecimal.ZERO, BigDecimal.ZERO)));

            ConsumptionSummaryResponse res = service.getConsumptionSummary(ORG_ID, ALLOCATION_ID);
            assertThat(res.status()).isEqualTo("OK");
            assertThat(res.consumptionRate()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("セキュリティ")
    class Security {

        @Test
        @DisplayName("BUDGET_VIEW 権限なし → BUDGET_VIEW_REQUIRED (403)")
        void view権限なし_403() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);

            assertThatThrownBy(() -> service.getConsumptionSummary(ORG_ID, ALLOCATION_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftBudgetErrorCode.BUDGET_VIEW_REQUIRED);
        }

        @Test
        @DisplayName("別組織の allocation → ALLOCATION_NOT_FOUND (404)")
        void 別組織_404() {
            givenBudgetViewAllowed();
            given(allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getConsumptionSummary(ORG_ID, ALLOCATION_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftBudgetErrorCode.ALLOCATION_NOT_FOUND);
        }
    }
}
