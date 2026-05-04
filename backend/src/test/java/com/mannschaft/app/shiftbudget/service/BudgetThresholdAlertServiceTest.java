package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.dto.AlertResponse;
import com.mannschaft.app.shiftbudget.entity.BudgetThresholdAlertEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.repository.BudgetThresholdAlertRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link BudgetThresholdAlertService} 単体テスト（Phase 9-δ 第2段、API #9 / #10）。
 *
 * <p>カバレッジ:</p>
 * <ul>
 *   <li>list: BUDGET_VIEW 権限ありで一覧返却</li>
 *   <li>list: BUDGET_VIEW 権限なし → 403</li>
 *   <li>acknowledge: BUDGET_ADMIN 権限ありで成功 + 監査ログ記録</li>
 *   <li>acknowledge: 別組織の alert ID → 404 (IDOR 対策、多テナント分離)</li>
 *   <li>acknowledge: BUDGET_ADMIN 権限なし → 403</li>
 *   <li>acknowledge: alert 自体が存在しない → 404</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BudgetThresholdAlertService 単体テスト")
class BudgetThresholdAlertServiceTest {

    private static final Long ORG_ID = 1L;
    private static final Long OTHER_ORG_ID = 99L;
    private static final Long ALERT_ID = 50L;
    private static final Long ALLOCATION_ID = 42L;
    private static final Long USER_ID = 100L;

    @Mock
    private BudgetThresholdAlertRepository alertRepository;
    @Mock
    private ShiftBudgetAllocationRepository allocationRepository;
    @Mock
    private ShiftBudgetFeatureService featureService;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private AuditLogService auditLogService;

    private BudgetThresholdAlertService service;

    @BeforeEach
    void setUp() {
        service = new BudgetThresholdAlertService(
                alertRepository, allocationRepository, featureService,
                accessControlService, auditLogService);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private BudgetThresholdAlertEntity sampleAlert() {
        return BudgetThresholdAlertEntity.builder()
                .allocationId(ALLOCATION_ID)
                .thresholdPercent(80)
                .triggeredAt(LocalDateTime.now())
                .consumedAmountAtTrigger(new BigDecimal("80000"))
                .notifiedUserIds("[10,11]")
                .build();
    }

    private ShiftBudgetAllocationEntity sampleAllocation(Long orgId) {
        return ShiftBudgetAllocationEntity.builder()
                .organizationId(orgId).teamId(12L)
                .fiscalYearId(3L).budgetCategoryId(17L)
                .periodStart(LocalDate.of(2026, 6, 1))
                .periodEnd(LocalDate.of(2026, 6, 30))
                .allocatedAmount(new BigDecimal("100000"))
                .consumedAmount(new BigDecimal("80000"))
                .confirmedAmount(BigDecimal.ZERO)
                .currency("JPY").createdBy(1L).version(0L)
                .build();
    }

    @Test
    @DisplayName("list: BUDGET_VIEW 権限ありで未承認警告一覧を返却")
    void list_正常系() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        // checkPermission は void 。例外なしで成功扱い
        given(alertRepository.findUnacknowledgedByOrg(eq(ORG_ID), any(Pageable.class)))
                .willReturn(List.of(sampleAlert()));

        List<AlertResponse> result = service.list(ORG_ID, 0, 20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).allocationId()).isEqualTo(ALLOCATION_ID);
        assertThat(result.get(0).thresholdPercent()).isEqualTo(80);
        verify(featureService).requireEnabled(ORG_ID);
    }

    @Test
    @DisplayName("list: BUDGET_VIEW 権限なし → BUDGET_VIEW_REQUIRED (403)")
    void list_権限なし() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService)
                .checkPermission(USER_ID, ORG_ID, "ORGANIZATION", "BUDGET_VIEW");

        assertThatThrownBy(() -> service.list(ORG_ID, 0, 20))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode())
                        .isEqualTo(ShiftBudgetErrorCode.BUDGET_VIEW_REQUIRED.getCode()));
    }

    @Test
    @DisplayName("acknowledge: BUDGET_ADMIN 権限ありで成功 + 監査ログ記録")
    void acknowledge_正常系() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        BudgetThresholdAlertEntity alert = sampleAlert();
        given(alertRepository.findById(ALERT_ID)).willReturn(Optional.of(alert));
        given(allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                .willReturn(Optional.of(sampleAllocation(ORG_ID)));
        given(alertRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        AlertResponse result = service.acknowledge(ORG_ID, ALERT_ID, "了解、来月予算追加");

        assertThat(result.acknowledgedBy()).isEqualTo(USER_ID);
        assertThat(result.acknowledgedAt()).isNotNull();
        verify(auditLogService).record(eq("BUDGET_THRESHOLD_ALERT_ACKNOWLEDGED"),
                eq(USER_ID), any(), any(), eq(ORG_ID), any(), any(), any(), any());
    }

    @Test
    @DisplayName("acknowledge: 別組織の alert ID → ALERT_NOT_FOUND (IDOR 対策)")
    void acknowledge_別組織は404() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        given(alertRepository.findById(ALERT_ID)).willReturn(Optional.of(sampleAlert()));
        // allocation は ORG_ID では見つからない（実は OTHER_ORG_ID 所属だが、ORG_ID 検索では Optional.empty）
        given(allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_ID, ORG_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.acknowledge(ORG_ID, ALERT_ID, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode())
                        .isEqualTo(ShiftBudgetErrorCode.ALERT_NOT_FOUND.getCode()));

        verify(alertRepository, never()).save(any());
    }

    @Test
    @DisplayName("acknowledge: BUDGET_ADMIN 権限なし → BUDGET_ADMIN_REQUIRED (403)")
    void acknowledge_権限なし() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService)
                .checkPermission(USER_ID, ORG_ID, "ORGANIZATION", "BUDGET_ADMIN");

        assertThatThrownBy(() -> service.acknowledge(ORG_ID, ALERT_ID, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode())
                        .isEqualTo(ShiftBudgetErrorCode.BUDGET_ADMIN_REQUIRED.getCode()));

        verify(alertRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("acknowledge: alert 自体が存在しない → ALERT_NOT_FOUND")
    void acknowledge_alert不在() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        given(alertRepository.findById(ALERT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.acknowledge(ORG_ID, ALERT_ID, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode())
                        .isEqualTo(ShiftBudgetErrorCode.ALERT_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("list: SYSTEM_ADMIN は権限チェックを bypass")
    void list_SYSTEM_ADMIN() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);
        given(alertRepository.findUnacknowledgedByOrg(eq(ORG_ID), any(Pageable.class)))
                .willReturn(List.of(sampleAlert(), sampleAlert()));

        List<AlertResponse> result = service.list(ORG_ID, 0, 20);

        assertThat(result).hasSize(2);
        verify(accessControlService, never()).checkPermission(any(), any(), any(), any());
    }
}
