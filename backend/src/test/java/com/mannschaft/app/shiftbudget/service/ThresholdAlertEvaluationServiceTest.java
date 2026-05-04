package com.mannschaft.app.shiftbudget.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.budget.entity.BudgetConfigEntity;
import com.mannschaft.app.budget.repository.BudgetConfigRepository;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.shiftbudget.entity.BudgetThresholdAlertEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.repository.BudgetThresholdAlertRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link ThresholdAlertEvaluationService} 単体テスト（Phase 9-δ 第2段）。
 *
 * <p>カバレッジ:</p>
 * <ul>
 *   <li>80% 到達 → warn80 alert を 1 件発火</li>
 *   <li>100% 到達 → warn80 + exceeded の 2 件発火</li>
 *   <li>120% 到達 → warn80 + exceeded + severeExceeded の 3 件発火</li>
 *   <li>既存 alert あり → skip（重複発火しない）</li>
 *   <li>未閾値 (50%) → 発火なし</li>
 *   <li>論理削除済み allocation → スキップ（fail-safe）</li>
 *   <li>予算ゼロ円 + 消化発生 → 100% / 120% 相当として発火</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ThresholdAlertEvaluationService 単体テスト")
class ThresholdAlertEvaluationServiceTest {

    private static final Long ALLOCATION_ID = 42L;
    private static final Long ORG_ID = 1L;
    private static final Long TEAM_ID = 12L;

    @Mock
    private ShiftBudgetAllocationRepository allocationRepository;
    @Mock
    private BudgetThresholdAlertRepository alertRepository;
    @Mock
    private BudgetConfigRepository budgetConfigRepository;
    @Mock
    private UserRoleRepository userRoleRepository;
    @Mock
    private NotificationHelper notificationHelper;
    @Mock
    private AuditLogService auditLogService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ThresholdAlertEvaluationService service;

    @BeforeEach
    void setUp() {
        service = new ThresholdAlertEvaluationService(
                allocationRepository, alertRepository, budgetConfigRepository,
                userRoleRepository, notificationHelper, auditLogService, objectMapper);
    }

    private ShiftBudgetAllocationEntity allocationWith(BigDecimal allocated, BigDecimal consumed) {
        ShiftBudgetAllocationEntity alloc = ShiftBudgetAllocationEntity.builder()
                .organizationId(ORG_ID)
                .teamId(TEAM_ID)
                .fiscalYearId(3L)
                .budgetCategoryId(17L)
                .periodStart(LocalDate.of(2026, 6, 1))
                .periodEnd(LocalDate.of(2026, 6, 30))
                .allocatedAmount(allocated)
                .consumedAmount(consumed)
                .confirmedAmount(BigDecimal.ZERO)
                .currency("JPY")
                .createdBy(1L)
                .version(0L)
                .build();
        // テストでは reflection で id を注入（BaseEntity#id は通常 DB 採番のため builder では入らない）
        try {
            java.lang.reflect.Field idField =
                    alloc.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(alloc, ALLOCATION_ID);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return alloc;
    }

    @Test
    @DisplayName("50% 消化 → 閾値未到達のため発火なし")
    void 未閾値() {
        ShiftBudgetAllocationEntity alloc = allocationWith(
                new BigDecimal("100000"), new BigDecimal("50000"));
        given(allocationRepository.findById(ALLOCATION_ID)).willReturn(Optional.of(alloc));

        service.evaluateAndTrigger(ALLOCATION_ID);

        verify(alertRepository, never()).saveAndFlush(any());
        verifyNoInteractions(notificationHelper);
    }

    @Test
    @DisplayName("80% 到達 → warn80 alert を 1 件発火")
    void 閾値80発火() {
        ShiftBudgetAllocationEntity alloc = allocationWith(
                new BigDecimal("100000"), new BigDecimal("80000"));
        given(allocationRepository.findById(ALLOCATION_ID)).willReturn(Optional.of(alloc));
        given(alertRepository.findByAllocationIdAndThresholdPercent(eq(ALLOCATION_ID), anyInt()))
                .willReturn(Optional.empty());
        given(userRoleRepository.findAdminUserIdsByOrganizationId(ORG_ID)).willReturn(List.of(10L));
        given(userRoleRepository.findUserIdsByOrganizationIdAndPermissionName(ORG_ID, "BUDGET_ADMIN"))
                .willReturn(List.of(11L));

        service.evaluateAndTrigger(ALLOCATION_ID);

        // 80% のみ発火 (100/120 は未到達)
        ArgumentCaptor<BudgetThresholdAlertEntity> captor =
                ArgumentCaptor.forClass(BudgetThresholdAlertEntity.class);
        verify(alertRepository, times(1)).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getThresholdPercent()).isEqualTo(80);
        assertThat(captor.getValue().getAllocationId()).isEqualTo(ALLOCATION_ID);

        verify(notificationHelper, times(1)).notifyAll(
                eq(List.of(10L, 11L)), eq("SHIFT_BUDGET_THRESHOLD_ALERT"),
                any(), any(), any(), eq(ALLOCATION_ID), any(), eq(ORG_ID), any(), any());
    }

    @Test
    @DisplayName("100% 到達 → warn80 + exceeded の 2 件発火 + workflow_id 未設定で WORKFLOW_NOT_CONFIGURED 監査")
    void 閾値100発火_ワークフロー未設定() {
        ShiftBudgetAllocationEntity alloc = allocationWith(
                new BigDecimal("100000"), new BigDecimal("100000"));
        given(allocationRepository.findById(ALLOCATION_ID)).willReturn(Optional.of(alloc));
        given(alertRepository.findByAllocationIdAndThresholdPercent(eq(ALLOCATION_ID), anyInt()))
                .willReturn(Optional.empty());
        given(userRoleRepository.findAdminUserIdsByOrganizationId(ORG_ID)).willReturn(List.of(10L));
        given(userRoleRepository.findUserIdsByOrganizationIdAndPermissionName(ORG_ID, "BUDGET_ADMIN"))
                .willReturn(List.of());
        given(budgetConfigRepository.findByScopeTypeAndScopeId("ORGANIZATION", ORG_ID))
                .willReturn(Optional.empty());

        service.evaluateAndTrigger(ALLOCATION_ID);

        verify(alertRepository, times(2)).saveAndFlush(any());  // 80 + 100
        verify(auditLogService, times(2)).record(eq("BUDGET_THRESHOLD_ALERT_TRIGGERED"),
                any(), any(), any(), any(), any(), any(), any(), any());
        verify(auditLogService, times(1)).record(eq("WORKFLOW_NOT_CONFIGURED"),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("120% 到達 → warn80 + exceeded + severeExceeded の 3 件発火")
    void 閾値120発火() {
        ShiftBudgetAllocationEntity alloc = allocationWith(
                new BigDecimal("100000"), new BigDecimal("130000"));
        given(allocationRepository.findById(ALLOCATION_ID)).willReturn(Optional.of(alloc));
        given(alertRepository.findByAllocationIdAndThresholdPercent(eq(ALLOCATION_ID), anyInt()))
                .willReturn(Optional.empty());
        given(userRoleRepository.findAdminUserIdsByOrganizationId(ORG_ID)).willReturn(List.of(10L));
        given(userRoleRepository.findUserIdsByOrganizationIdAndPermissionName(ORG_ID, "BUDGET_ADMIN"))
                .willReturn(List.of());
        given(budgetConfigRepository.findByScopeTypeAndScopeId("ORGANIZATION", ORG_ID))
                .willReturn(Optional.of(BudgetConfigEntity.builder()
                        .scopeType("ORGANIZATION").scopeId(ORG_ID)
                        .overLimitWorkflowId(99L).build()));

        service.evaluateAndTrigger(ALLOCATION_ID);

        verify(alertRepository, times(3)).saveAndFlush(any());  // 80 + 100 + 120
        // workflow_id 設定済 → WORKFLOW_NOT_CONFIGURED は記録されない
        verify(auditLogService, never()).record(eq("WORKFLOW_NOT_CONFIGURED"),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("既存 80% alert あり → 80 は skip、100% も到達なら 100 のみ発火")
    void 重複防止_既存ありはスキップ() {
        ShiftBudgetAllocationEntity alloc = allocationWith(
                new BigDecimal("100000"), new BigDecimal("100000"));
        given(allocationRepository.findById(ALLOCATION_ID)).willReturn(Optional.of(alloc));
        // 80% は既存あり、100% は未発火
        given(alertRepository.findByAllocationIdAndThresholdPercent(ALLOCATION_ID, 80))
                .willReturn(Optional.of(BudgetThresholdAlertEntity.builder()
                        .allocationId(ALLOCATION_ID).thresholdPercent(80).build()));
        given(alertRepository.findByAllocationIdAndThresholdPercent(ALLOCATION_ID, 100))
                .willReturn(Optional.empty());
        given(userRoleRepository.findAdminUserIdsByOrganizationId(ORG_ID)).willReturn(List.of(10L));
        given(userRoleRepository.findUserIdsByOrganizationIdAndPermissionName(ORG_ID, "BUDGET_ADMIN"))
                .willReturn(List.of());
        given(budgetConfigRepository.findByScopeTypeAndScopeId("ORGANIZATION", ORG_ID))
                .willReturn(Optional.empty());

        service.evaluateAndTrigger(ALLOCATION_ID);

        // 100 のみ INSERT (80 は skip)
        ArgumentCaptor<BudgetThresholdAlertEntity> captor =
                ArgumentCaptor.forClass(BudgetThresholdAlertEntity.class);
        verify(alertRepository, times(1)).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getThresholdPercent()).isEqualTo(100);
    }

    @Test
    @DisplayName("論理削除済 allocation → スキップ（fail-safe）")
    void 論理削除済はスキップ() {
        ShiftBudgetAllocationEntity alloc = allocationWith(
                new BigDecimal("100000"), new BigDecimal("80000"));
        alloc.markDeleted();
        given(allocationRepository.findById(ALLOCATION_ID)).willReturn(Optional.of(alloc));

        service.evaluateAndTrigger(ALLOCATION_ID);

        verify(alertRepository, never()).saveAndFlush(any());
    }

    @Nested
    @DisplayName("予算ゼロ円の境界ケース")
    class 予算ゼロ {

        @Test
        @DisplayName("予算 0 + 消化 0 → 発火なし")
        void 予算0消化0は発火なし() {
            ShiftBudgetAllocationEntity alloc = allocationWith(
                    BigDecimal.ZERO, BigDecimal.ZERO);
            given(allocationRepository.findById(ALLOCATION_ID)).willReturn(Optional.of(alloc));

            service.evaluateAndTrigger(ALLOCATION_ID);

            verify(alertRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("予算 0 + 消化 > 0 → 100% / 120% 相当として 2 件発火")
        void 予算0消化ありは100と120を発火() {
            ShiftBudgetAllocationEntity alloc = allocationWith(
                    BigDecimal.ZERO, new BigDecimal("1000"));
            given(allocationRepository.findById(ALLOCATION_ID)).willReturn(Optional.of(alloc));
            given(alertRepository.findByAllocationIdAndThresholdPercent(eq(ALLOCATION_ID), anyInt()))
                    .willReturn(Optional.empty());
            given(userRoleRepository.findAdminUserIdsByOrganizationId(ORG_ID)).willReturn(List.of(10L));
            given(userRoleRepository.findUserIdsByOrganizationIdAndPermissionName(ORG_ID, "BUDGET_ADMIN"))
                    .willReturn(List.of());
            given(budgetConfigRepository.findByScopeTypeAndScopeId("ORGANIZATION", ORG_ID))
                    .willReturn(Optional.empty());

            service.evaluateAndTrigger(ALLOCATION_ID);

            verify(alertRepository, times(2)).saveAndFlush(any());  // 100 + 120
        }
    }

    @Test
    @DisplayName("受信ロール 0 名 → INSERT は実行するが notifyAll は呼ばれない")
    void 受信者ゼロ_alert発火のみ() {
        ShiftBudgetAllocationEntity alloc = allocationWith(
                new BigDecimal("100000"), new BigDecimal("80000"));
        given(allocationRepository.findById(ALLOCATION_ID)).willReturn(Optional.of(alloc));
        given(alertRepository.findByAllocationIdAndThresholdPercent(eq(ALLOCATION_ID), anyInt()))
                .willReturn(Optional.empty());
        given(userRoleRepository.findAdminUserIdsByOrganizationId(ORG_ID)).willReturn(List.of());
        given(userRoleRepository.findUserIdsByOrganizationIdAndPermissionName(ORG_ID, "BUDGET_ADMIN"))
                .willReturn(List.of());

        service.evaluateAndTrigger(ALLOCATION_ID);

        verify(alertRepository, times(1)).saveAndFlush(any());
        // 受信ロール 0 名のため notifyAll は呼ばれない
        verifyNoInteractions(notificationHelper);
    }
}
