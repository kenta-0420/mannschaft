package com.mannschaft.app.budget.service;

import com.mannschaft.app.budget.BudgetFiscalYearStatus;
import com.mannschaft.app.budget.BudgetMapper;
import com.mannschaft.app.budget.dto.CreateFiscalYearRequest;
import com.mannschaft.app.budget.entity.BudgetFiscalYearEntity;
import com.mannschaft.app.budget.repository.BudgetFiscalYearRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link BudgetFiscalYearService} 単体テスト。
 *
 * <p>主眼: toBuilder().build()→save による INSERT 化バグ（BaseEntity 継承の id 欠落）の再発防止。
 * 更新は findById で取得した管理対象（managed）エンティティを直接ミューテートし、
 * 同一インスタンスを save に渡す（＝id 保持＝UPDATE）ことを固定する。
 * あわせて CLOSED 年度更新ガードという既存副作用が保持されることも検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BudgetFiscalYearService 単体テスト")
class BudgetFiscalYearServiceTest {

    @Mock
    private BudgetFiscalYearRepository fiscalYearRepository;
    @Mock
    private BudgetCategoryService categoryService;
    @Mock
    private BudgetMapper budgetMapper;
    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private BudgetFiscalYearService service;

    private MockedStatic<SecurityUtils> securityUtilsMock;

    private static final Long CURRENT_USER_ID = 7L;
    private static final Long FY_ID = 55L;
    private static final Long SCOPE_ID = 10L;
    private static final String SCOPE_TYPE = "TEAM";

    @BeforeEach
    void setUp() {
        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(CURRENT_USER_ID);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    private BudgetFiscalYearEntity existingFiscalYear(BudgetFiscalYearStatus status) {
        BudgetFiscalYearEntity entity = BudgetFiscalYearEntity.builder()
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .name("2025年度")
                .startDate(LocalDate.of(2025, 4, 1))
                .endDate(LocalDate.of(2026, 3, 31))
                .status(status)
                .createdBy(CURRENT_USER_ID)
                .build();
        // BaseEntity の id は @GeneratedValue・private のため反射で永続化済み状態を再現する
        ReflectionTestUtils.setField(entity, "id", FY_ID);
        return entity;
    }

    @Test
    @DisplayName("update: findById の同一インスタンスを id 保持のまま save する（INSERT 化しない）")
    void update_mutatesManagedEntityAndPreservesId() {
        BudgetFiscalYearEntity existing = existingFiscalYear(BudgetFiscalYearStatus.OPEN);
        given(fiscalYearRepository.findById(FY_ID)).willReturn(Optional.of(existing));
        given(fiscalYearRepository.save(any(BudgetFiscalYearEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));
        // budgetMapper.toFiscalYearResponse は未スタブ＝null 返却で十分（戻り値は本テストで検証しない）

        CreateFiscalYearRequest request = new CreateFiscalYearRequest(
                "2025年度（改）",
                LocalDate.of(2025, 4, 1),
                LocalDate.of(2026, 3, 31),
                SCOPE_ID,
                SCOPE_TYPE
        );

        service.update(FY_ID, request);

        ArgumentCaptor<BudgetFiscalYearEntity> captor = ArgumentCaptor.forClass(BudgetFiscalYearEntity.class);
        verify(fiscalYearRepository).save(captor.capture());
        BudgetFiscalYearEntity saved = captor.getValue();

        // 最重要: save に渡るのは findById の同一インスタンス（＝managed・id 保持＝UPDATE）
        assertThat(saved).isSameAs(existing);
        assertThat(saved.getId()).isEqualTo(FY_ID);
        assertThat(saved.getName()).isEqualTo("2025年度（改）");
        // status は更新対象外で温存される
        assertThat(saved.getStatus()).isEqualTo(BudgetFiscalYearStatus.OPEN);

        verify(accessControlService).checkAdminOrAbove(CURRENT_USER_ID, SCOPE_ID, SCOPE_TYPE);
    }

    @Test
    @DisplayName("update: CLOSED 年度は更新できない（checkOpen ガードが保持される・save しない）")
    void update_rejectsClosedFiscalYear() {
        BudgetFiscalYearEntity existing = existingFiscalYear(BudgetFiscalYearStatus.CLOSED);
        given(fiscalYearRepository.findById(FY_ID)).willReturn(Optional.of(existing));

        CreateFiscalYearRequest request = new CreateFiscalYearRequest(
                "2025年度（改）",
                LocalDate.of(2025, 4, 1),
                LocalDate.of(2026, 3, 31),
                SCOPE_ID,
                SCOPE_TYPE
        );

        assertThatThrownBy(() -> service.update(FY_ID, request))
                .isInstanceOf(BusinessException.class);

        verify(fiscalYearRepository, never()).save(any());
    }
}
