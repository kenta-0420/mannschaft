package com.mannschaft.app.budget.service;

import com.mannschaft.app.budget.BudgetMapper;
import com.mannschaft.app.budget.entity.BudgetFiscalYearEntity;
import com.mannschaft.app.budget.repository.BudgetAllocationRepository;
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
 * {@link BudgetAllocationService#listByFiscalYear} の認可単体テスト（CMP-260917-2102 Phase 1 の追撃）。
 *
 * <p>実機で MEMBER が予算配分一覧を取得できることを確認済み。予算はスコープ問わず
 * DEPUTY_ADMIN 限定のため無条件 checkAdminOrAbove に是正する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BudgetAllocationService 認可単体テスト（listByFiscalYear）")
class BudgetAllocationServiceListByFiscalYearAuthzTest {

    @Mock private BudgetAllocationRepository allocationRepository;
    @Mock private BudgetFiscalYearService fiscalYearService;
    @Mock private BudgetCategoryService categoryService;
    @Mock private BudgetMapper budgetMapper;
    @Mock private AccessControlService accessControlService;

    @InjectMocks
    private BudgetAllocationService service;

    private static final Long SCOPE_ID = 9L;
    private static final String SCOPE_TYPE = "ORGANIZATION";
    private static final Long FISCAL_YEAR_ID = 1L;
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
    @DisplayName("ORGANIZATIONスコープ: MEMBERは403（COMMON_002）で拒否される")
    void member_isForbidden() {
        given(fiscalYearService.findById(FISCAL_YEAR_ID)).willReturn(fiscalYear());
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(accessControlService).checkAdminOrAbove(eq(ACTOR_ID), eq(SCOPE_ID), eq(SCOPE_TYPE));

        assertThatThrownBy(() -> service.listByFiscalYear(FISCAL_YEAR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);
    }

    @Test
    @DisplayName("ORGANIZATIONスコープ: ADMINは200相当で取得できる")
    void admin_isAllowed() {
        given(fiscalYearService.findById(FISCAL_YEAR_ID)).willReturn(fiscalYear());
        doNothing().when(accessControlService).checkAdminOrAbove(eq(ACTOR_ID), eq(SCOPE_ID), eq(SCOPE_TYPE));
        given(allocationRepository.findByFiscalYearId(FISCAL_YEAR_ID)).willReturn(Collections.emptyList());

        assertThatCode(() -> service.listByFiscalYear(FISCAL_YEAR_ID)).doesNotThrowAnyException();
    }
}
