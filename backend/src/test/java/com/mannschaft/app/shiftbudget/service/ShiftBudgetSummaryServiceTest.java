package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.dto.ConsumptionSummaryResponse;
import com.mannschaft.app.shiftbudget.entity.BudgetThresholdAlertEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetConsumptionEntity;
import com.mannschaft.app.shiftbudget.repository.BudgetThresholdAlertRepository;
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
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

/**
 * {@link ShiftBudgetSummaryService} 単体テスト（Phase 9-δ 第3段で正規化完了）。
 *
 * <p>カバレッジ:</p>
 * <ul>
 *   <li>BUDGET_ADMIN 保有時: by_user 実集計 + flags 空配列 + alerts 実データ</li>
 *   <li>BUDGET_VIEW のみ: by_user 空配列 + flags=BY_USER_HIDDEN + alerts 実データ</li>
 *   <li>SystemAdmin: BUDGET_ADMIN 同様（by_user 実集計）</li>
 *   <li>by_user 集計: user_id 別 SUM(amount)/SUM(hours)、CANCELLED 除外、user_id 昇順</li>
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
    private BudgetThresholdAlertRepository alertRepository;
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

    /** BUDGET_VIEW のみ保有（BUDGET_ADMIN 不保持）。Service は has-permission の二段階呼出を行う。 */
    private void givenBudgetViewerOnly() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        // BUDGET_VIEW チェック → true、BUDGET_ADMIN チェック → false の2回呼ばれる
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        // BUDGET_VIEW: 例外を投げない、BUDGET_ADMIN: 例外を投げる
        lenient().doNothing().when(accessControlService)
                .checkPermission(USER_ID, ORG_ID, "ORGANIZATION", "BUDGET_VIEW");
        lenient().doThrow(new BusinessException(ShiftBudgetErrorCode.BUDGET_ADMIN_REQUIRED))
                .when(accessControlService)
                .checkPermission(USER_ID, ORG_ID, "ORGANIZATION", "BUDGET_ADMIN");
        // alerts 実集計の戻り（デフォルトで空）
        lenient().when(alertRepository.findByAllocationIdOrderByTriggeredAtDesc(ALLOCATION_ID))
                .thenReturn(Collections.emptyList());
    }

    /** BUDGET_ADMIN 保有（BUDGET_VIEW も自動保有）。 */
    private void givenBudgetAdmin() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        lenient().doNothing().when(accessControlService)
                .checkPermission(USER_ID, ORG_ID, "ORGANIZATION", "BUDGET_VIEW");
        lenient().doNothing().when(accessControlService)
                .checkPermission(USER_ID, ORG_ID, "ORGANIZATION", "BUDGET_ADMIN");
        lenient().when(alertRepository.findByAllocationIdOrderByTriggeredAtDesc(ALLOCATION_ID))
                .thenReturn(Collections.emptyList());
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

    private ShiftBudgetConsumptionEntity consumptionWith(Long userId, BigDecimal amount,
                                                          BigDecimal hours,
                                                          ShiftBudgetConsumptionStatus status) {
        return ShiftBudgetConsumptionEntity.builder()
                .allocationId(ALLOCATION_ID)
                .shiftId(700L)
                .slotId(800L)
                .userId(userId)
                .hourlyRateSnapshot(new BigDecimal("1200.00"))
                .hours(hours)
                .amount(amount)
                .currency("JPY")
                .status(status)
                .recordedAt(LocalDateTime.of(2026, 6, 1, 9, 0))
                .build();
    }

    @Nested
    @DisplayName("権限による表示切替（Phase 9-δ 第3段）")
    class PermissionGating {

        @Test
        @DisplayName("BUDGET_VIEW のみ: by_user=[] + flags=BY_USER_HIDDEN")
        void viewerのみ_byUser空_flagsBY_USER_HIDDEN() {
            givenBudgetViewerOnly();
            given(allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                    .willReturn(Optional.of(entityWith(
                            new BigDecimal("300000"),
                            new BigDecimal("245000"),
                            new BigDecimal("200000"))));

            ConsumptionSummaryResponse res = service.getConsumptionSummary(ORG_ID, ALLOCATION_ID);

            assertThat(res.byUser()).isNotNull().isEmpty();
            assertThat(res.flags()).contains(ShiftBudgetSummaryService.FLAG_BY_USER_HIDDEN);
        }

        @Test
        @DisplayName("BUDGET_ADMIN: by_user 実集計 + flags 空配列")
        void admin_byUser実集計_flags空() {
            givenBudgetAdmin();
            given(allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                    .willReturn(Optional.of(entityWith(
                            new BigDecimal("300000"),
                            new BigDecimal("245000"),
                            new BigDecimal("200000"))));
            given(consumptionRepository.findByAllocationIdAndStatusInAndDeletedAtIsNull(
                    ALLOCATION_ID,
                    List.of(ShiftBudgetConsumptionStatus.PLANNED, ShiftBudgetConsumptionStatus.CONFIRMED)))
                    .willReturn(List.of(
                            consumptionWith(5L, new BigDecimal("80000"), new BigDecimal("66.67"),
                                    ShiftBudgetConsumptionStatus.CONFIRMED),
                            consumptionWith(6L, new BigDecimal("60000"), new BigDecimal("50.00"),
                                    ShiftBudgetConsumptionStatus.PLANNED)));

            ConsumptionSummaryResponse res = service.getConsumptionSummary(ORG_ID, ALLOCATION_ID);

            assertThat(res.byUser()).hasSize(2);
            assertThat(res.byUser().get(0).userId()).isEqualTo(5L);
            assertThat(res.byUser().get(0).amount()).isEqualByComparingTo("80000");
            assertThat(res.byUser().get(0).hours()).isEqualByComparingTo("66.67");
            assertThat(res.byUser().get(1).userId()).isEqualTo(6L);
            assertThat(res.flags()).isEmpty();
        }

        @Test
        @DisplayName("SystemAdmin: BUDGET_ADMIN 相当（by_user 実集計）")
        void systemAdmin_byUser実集計() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);
            given(allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                    .willReturn(Optional.of(entityWith(
                            new BigDecimal("100000"),
                            new BigDecimal("50000"),
                            BigDecimal.ZERO)));
            given(consumptionRepository.findByAllocationIdAndStatusInAndDeletedAtIsNull(
                    ALLOCATION_ID,
                    List.of(ShiftBudgetConsumptionStatus.PLANNED, ShiftBudgetConsumptionStatus.CONFIRMED)))
                    .willReturn(List.of(
                            consumptionWith(7L, new BigDecimal("50000"), new BigDecimal("40.00"),
                                    ShiftBudgetConsumptionStatus.CONFIRMED)));
            given(alertRepository.findByAllocationIdOrderByTriggeredAtDesc(ALLOCATION_ID))
                    .willReturn(Collections.emptyList());

            ConsumptionSummaryResponse res = service.getConsumptionSummary(ORG_ID, ALLOCATION_ID);

            assertThat(res.byUser()).hasSize(1);
            assertThat(res.byUser().get(0).userId()).isEqualTo(7L);
            assertThat(res.flags()).isEmpty();
        }
    }

    @Nested
    @DisplayName("by_user 集計ロジック")
    class ByUserAggregation {

        @Test
        @DisplayName("同一ユーザーの複数レコードは SUM される")
        void 同一ユーザーSUM() {
            givenBudgetAdmin();
            given(allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                    .willReturn(Optional.of(entityWith(
                            new BigDecimal("300000"),
                            new BigDecimal("100000"),
                            BigDecimal.ZERO)));
            given(consumptionRepository.findByAllocationIdAndStatusInAndDeletedAtIsNull(
                    ALLOCATION_ID,
                    List.of(ShiftBudgetConsumptionStatus.PLANNED, ShiftBudgetConsumptionStatus.CONFIRMED)))
                    .willReturn(List.of(
                            consumptionWith(5L, new BigDecimal("30000"), new BigDecimal("25.00"),
                                    ShiftBudgetConsumptionStatus.PLANNED),
                            consumptionWith(5L, new BigDecimal("20000"), new BigDecimal("16.67"),
                                    ShiftBudgetConsumptionStatus.CONFIRMED)));

            ConsumptionSummaryResponse res = service.getConsumptionSummary(ORG_ID, ALLOCATION_ID);

            assertThat(res.byUser()).hasSize(1);
            assertThat(res.byUser().get(0).userId()).isEqualTo(5L);
            assertThat(res.byUser().get(0).amount()).isEqualByComparingTo("50000");
            assertThat(res.byUser().get(0).hours()).isEqualByComparingTo("41.67");
        }

        @Test
        @DisplayName("user_id 昇順で返る")
        void userId昇順() {
            givenBudgetAdmin();
            given(allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                    .willReturn(Optional.of(entityWith(
                            new BigDecimal("300000"),
                            new BigDecimal("100000"),
                            BigDecimal.ZERO)));
            given(consumptionRepository.findByAllocationIdAndStatusInAndDeletedAtIsNull(
                    ALLOCATION_ID,
                    List.of(ShiftBudgetConsumptionStatus.PLANNED, ShiftBudgetConsumptionStatus.CONFIRMED)))
                    // あえて user_id の降順で渡しても昇順で返ること
                    .willReturn(List.of(
                            consumptionWith(9L, new BigDecimal("10000"), new BigDecimal("8.00"),
                                    ShiftBudgetConsumptionStatus.PLANNED),
                            consumptionWith(3L, new BigDecimal("20000"), new BigDecimal("16.00"),
                                    ShiftBudgetConsumptionStatus.PLANNED),
                            consumptionWith(7L, new BigDecimal("15000"), new BigDecimal("12.00"),
                                    ShiftBudgetConsumptionStatus.PLANNED)));

            ConsumptionSummaryResponse res = service.getConsumptionSummary(ORG_ID, ALLOCATION_ID);

            assertThat(res.byUser()).extracting(d -> d.userId()).containsExactly(3L, 7L, 9L);
        }
    }

    @Nested
    @DisplayName("status 4段階判定")
    class StatusJudgement {

        @Test
        @DisplayName("rate < 0.80 → OK")
        void ok() {
            givenBudgetViewerOnly();
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
            givenBudgetViewerOnly();
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
            givenBudgetViewerOnly();
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
            givenBudgetViewerOnly();
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
            givenBudgetViewerOnly();
            given(allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                    .willReturn(Optional.of(entityWith(BigDecimal.ZERO,
                            BigDecimal.ZERO, BigDecimal.ZERO)));

            ConsumptionSummaryResponse res = service.getConsumptionSummary(ORG_ID, ALLOCATION_ID);
            assertThat(res.status()).isEqualTo("OK");
            assertThat(res.consumptionRate()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("alerts 実集計")
    class AlertsAggregation {

        @Test
        @DisplayName("alerts は alertRepository から実データを取得して返却")
        void alerts実データ() {
            givenBudgetAdmin();
            given(allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                    .willReturn(Optional.of(entityWith(
                            new BigDecimal("100000"),
                            new BigDecimal("85000"),
                            BigDecimal.ZERO)));
            given(consumptionRepository.findByAllocationIdAndStatusInAndDeletedAtIsNull(
                    ALLOCATION_ID,
                    List.of(ShiftBudgetConsumptionStatus.PLANNED, ShiftBudgetConsumptionStatus.CONFIRMED)))
                    .willReturn(Collections.emptyList());
            BudgetThresholdAlertEntity alert = BudgetThresholdAlertEntity.builder()
                    .allocationId(ALLOCATION_ID)
                    .thresholdPercent(80)
                    .triggeredAt(LocalDateTime.of(2026, 6, 25, 14, 30))
                    .consumedAmountAtTrigger(new BigDecimal("85000"))
                    .notifiedUserIds("[1,2]")
                    .build();
            given(alertRepository.findByAllocationIdOrderByTriggeredAtDesc(ALLOCATION_ID))
                    .willReturn(List.of(alert));

            ConsumptionSummaryResponse res = service.getConsumptionSummary(ORG_ID, ALLOCATION_ID);

            assertThat(res.alerts()).hasSize(1);
            assertThat(res.alerts().get(0).thresholdPercent()).isEqualTo(80);
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
            givenBudgetViewerOnly();
            given(allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getConsumptionSummary(ORG_ID, ALLOCATION_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftBudgetErrorCode.ALLOCATION_NOT_FOUND);
        }
    }
}
