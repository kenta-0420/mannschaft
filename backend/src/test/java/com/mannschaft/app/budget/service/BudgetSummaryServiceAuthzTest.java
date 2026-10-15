package com.mannschaft.app.budget.service;

import com.mannschaft.app.budget.entity.BudgetFiscalYearEntity;
import com.mannschaft.app.budget.repository.BudgetAllocationRepository;
import com.mannschaft.app.budget.repository.BudgetConfigRepository;
import com.mannschaft.app.budget.repository.BudgetTransactionRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

/**
 * {@link BudgetSummaryService#getFiscalYearSummary} の認可単体テスト（CMP-260917-1350 Phase 1）。
 *
 * <p>組織サイドバーで ADMIN/DEPUTY_ADMIN 限定表示している「予算」機能の GET が
 * {@code checkMembership} 止まりで MEMBER も閲覧できていた認可漏れを根治する。
 * 本テストは是正前は red（MEMBER で 403 が出ない）、是正後は green になる。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BudgetSummaryService 認可単体テスト（getFiscalYearSummary）")
class BudgetSummaryServiceAuthzTest {

    @Mock private BudgetTransactionRepository transactionRepository;
    @Mock private BudgetAllocationRepository allocationRepository;
    @Mock private BudgetConfigRepository configRepository;
    @Mock private BudgetFiscalYearService fiscalYearService;
    @Mock private BudgetCategoryService categoryService;
    @Mock private AccessControlService accessControlService;

    @InjectMocks
    private BudgetSummaryService service;

    private static final Long SCOPE_ID = 1L;
    private static final String SCOPE_TYPE = "TEAM";
    private static final Long FISCAL_YEAR_ID = 10L;
    private static final Long ACTOR_ID = 100L;

    private MockedStatic<SecurityUtils> securityUtils;

    @BeforeEach
    void setUp() {
        securityUtils = Mockito.mockStatic(SecurityUtils.class);
        securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(ACTOR_ID);
    }

    @AfterEach
    void tearDown() {
        securityUtils.close();
    }

    private BudgetFiscalYearEntity fiscalYear() {
        return BudgetFiscalYearEntity.builder()
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .name("2026年度")
                .startDate(LocalDate.of(2026, 4, 1))
                .endDate(LocalDate.of(2027, 3, 31))
                .createdBy(1L)
                .build();
    }

    @Test
    @DisplayName("MEMBER は 403（COMMON_002）で拒否される")
    void member_isForbidden() {
        given(fiscalYearService.findById(FISCAL_YEAR_ID)).willReturn(fiscalYear());
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(accessControlService).checkAdminOrAbove(eq(ACTOR_ID), eq(SCOPE_ID), eq(SCOPE_TYPE));

        assertThatThrownBy(() -> service.getFiscalYearSummary(FISCAL_YEAR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);
    }

    @Test
    @DisplayName("ADMIN は 200 相当で取得できる")
    void admin_isAllowed() {
        given(fiscalYearService.findById(FISCAL_YEAR_ID)).willReturn(fiscalYear());
        doNothing().when(accessControlService).checkAdminOrAbove(eq(ACTOR_ID), eq(SCOPE_ID), eq(SCOPE_TYPE));
        given(transactionRepository.findByFiscalYearId(FISCAL_YEAR_ID)).willReturn(Collections.emptyList());
        given(allocationRepository.findByFiscalYearId(FISCAL_YEAR_ID)).willReturn(Collections.emptyList());
        given(categoryService.listFlatByFiscalYear(FISCAL_YEAR_ID)).willReturn(Collections.emptyList());

        assertThatCode(() -> service.getFiscalYearSummary(FISCAL_YEAR_ID)).doesNotThrowAnyException();
    }
}
