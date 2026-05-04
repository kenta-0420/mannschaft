package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.dto.AllocationCreateRequest;
import com.mannschaft.app.shiftbudget.dto.AllocationUpdateRequest;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetRateQueryRepository;
import com.mannschaft.app.todo.repository.ProjectRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * F08.7 シフト予算 多テナント分離テスト（Phase 9-β）。
 *
 * <p>9-α で {@code ShiftBudgetCalcMultiTenantTest} として独立分割した方針を踏襲し、
 * 9-β の Allocation / Summary 系も別ファイルで多テナント検証を集約する（保守性向上）。</p>
 *
 * <p>カバレッジ:</p>
 * <ul>
 *   <li>別組織 ID で allocation 直接アクセス → 404 (IDOR 対策)</li>
 *   <li>別組織の team_id を作成リクエストに混入 → 404</li>
 *   <li>別組織のリソースを更新 → 404</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("F08.7 多テナント分離テスト (Phase 9-β)")
class ShiftBudgetMultiTenantTest {

    private static final Long USER_ID = 100L;
    private static final Long ORG_A = 1L;
    private static final Long ORG_B = 2L;
    private static final Long TEAM_OF_B = 999L;
    private static final Long ALLOCATION_OF_B = 9999L;

    @Mock
    private ShiftBudgetAllocationRepository allocationRepository;
    @Mock
    private ShiftBudgetConsumptionRepository consumptionRepository;
    @Mock
    private ShiftBudgetRateQueryRepository rateQueryRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ShiftBudgetFeatureService featureService;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private ShiftBudgetAllocationService service;

    @BeforeEach
    void setUpSecurity() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    /** 組織 A の権限を持つユーザーが組織 B の allocation を直叩き → 404 */
    @Test
    @DisplayName("別組織 allocation_id 直接 GET → ALLOCATION_NOT_FOUND (404)")
    void 別組織直接GET_404() {
        // ユーザーは ORG_A の BUDGET_VIEW 権限保有
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_A, "ORGANIZATION")).willReturn(true);
        // findByIdAndOrganizationIdAndDeletedAtIsNull(allocId_of_B, ORG_A) は別組織のため空を返す
        given(allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_OF_B, ORG_A))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAllocation(ORG_A, ALLOCATION_OF_B))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ShiftBudgetErrorCode.ALLOCATION_NOT_FOUND);
    }

    /** ORG_A コンテキストで ORG_B 配下の team_id を作成リクエストに混入 → 404 (IDOR 対策) */
    @Test
    @DisplayName("別組織 team_id を作成リクエストに混入 → ALLOCATION_NOT_FOUND (404)")
    void 別組織team_作成IDOR_404() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_A, "ORGANIZATION")).willReturn(true);
        // team_id=TEAM_OF_B は ORG_A に所属しないため count=0
        given(rateQueryRepository.countTeamInOrganization(TEAM_OF_B, ORG_A)).willReturn(0L);

        AllocationCreateRequest req = new AllocationCreateRequest(
                TEAM_OF_B, null, 3L, 17L,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                new BigDecimal("100000"), null, null);

        assertThatThrownBy(() -> service.createAllocation(ORG_A, req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ShiftBudgetErrorCode.ALLOCATION_NOT_FOUND);
    }

    /** 別組織の allocation を更新試行 → 404 */
    @Test
    @DisplayName("別組織 allocation を PUT → ALLOCATION_NOT_FOUND (404)")
    void 別組織更新_404() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_A, "ORGANIZATION")).willReturn(true);
        given(allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_OF_B, ORG_A))
                .willReturn(Optional.empty());

        AllocationUpdateRequest req = new AllocationUpdateRequest(
                new BigDecimal("100"), null, 0L);

        assertThatThrownBy(() -> service.updateAllocation(ORG_A, ALLOCATION_OF_B, req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ShiftBudgetErrorCode.ALLOCATION_NOT_FOUND);
    }

    /** 別組織の allocation を削除試行 → 404 */
    @Test
    @DisplayName("別組織 allocation を DELETE → ALLOCATION_NOT_FOUND (404)")
    void 別組織削除_404() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_A, "ORGANIZATION")).willReturn(true);
        given(allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(ALLOCATION_OF_B, ORG_A))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteAllocation(ORG_A, ALLOCATION_OF_B))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ShiftBudgetErrorCode.ALLOCATION_NOT_FOUND);
    }
}
