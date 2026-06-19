package com.mannschaft.app.budget.service;

import com.mannschaft.app.budget.BudgetCategoryType;
import com.mannschaft.app.budget.BudgetMapper;
import com.mannschaft.app.budget.dto.UpdateCategoryRequest;
import com.mannschaft.app.budget.entity.BudgetCategoryEntity;
import com.mannschaft.app.budget.repository.BudgetCategoryRepository;
import com.mannschaft.app.common.AccessControlService;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link BudgetCategoryService} 単体テスト。
 *
 * <p>主眼: toBuilder().build()→save による INSERT 化バグ（BaseEntity 継承の id 欠落）の再発防止。
 * 更新は findById で取得した管理対象（managed）エンティティを直接ミューテートし、
 * 同一インスタンスを save に渡す（＝id 保持＝UPDATE）ことを固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BudgetCategoryService 単体テスト")
class BudgetCategoryServiceTest {

    @Mock
    private BudgetCategoryRepository categoryRepository;
    @Mock
    private BudgetMapper budgetMapper;
    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private BudgetCategoryService service;

    private MockedStatic<SecurityUtils> securityUtilsMock;

    private static final Long CURRENT_USER_ID = 7L;
    private static final Long CATEGORY_ID = 42L;
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

    private BudgetCategoryEntity existingCategory() {
        BudgetCategoryEntity entity = BudgetCategoryEntity.builder()
                .fiscalYearId(100L)
                .name("旧名称")
                .categoryType(BudgetCategoryType.EXPENSE)
                .parentId(null)
                .sortOrder(3)
                .description("旧説明")
                .build();
        // BaseEntity の id は @GeneratedValue・private のため反射で永続化済み状態を再現する
        ReflectionTestUtils.setField(entity, "id", CATEGORY_ID);
        return entity;
    }

    @Test
    @DisplayName("update: findById の同一インスタンスを id 保持のまま save する（INSERT 化しない）")
    void update_mutatesManagedEntityAndPreservesId() {
        BudgetCategoryEntity existing = existingCategory();
        given(categoryRepository.findById(CATEGORY_ID)).willReturn(Optional.of(existing));
        // save はエコー（受領インスタンスをそのまま返す）。INSERT/UPDATE 区別は captor で id を見る
        given(categoryRepository.save(any(BudgetCategoryEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));
        // budgetMapper.toCategoryResponse は未スタブ＝null 返却で十分（戻り値は本テストで検証しない）

        UpdateCategoryRequest request = new UpdateCategoryRequest("新名称", 9, "新説明");

        service.update(CATEGORY_ID, request, SCOPE_ID, SCOPE_TYPE);

        ArgumentCaptor<BudgetCategoryEntity> captor = ArgumentCaptor.forClass(BudgetCategoryEntity.class);
        verify(categoryRepository).save(captor.capture());
        BudgetCategoryEntity saved = captor.getValue();

        // 最重要: save に渡るのは findById の同一インスタンス（＝managed・id 保持＝UPDATE）
        assertThat(saved).isSameAs(existing);
        assertThat(saved.getId()).isEqualTo(CATEGORY_ID);
        // ミューテートが反映されている
        assertThat(saved.getName()).isEqualTo("新名称");
        assertThat(saved.getSortOrder()).isEqualTo(9);
        assertThat(saved.getDescription()).isEqualTo("新説明");

        verify(accessControlService).checkAdminOrAbove(CURRENT_USER_ID, SCOPE_ID, SCOPE_TYPE);
    }

    @Test
    @DisplayName("update: sortOrder が null のとき既存値を温存する")
    void update_keepsExistingSortOrderWhenNull() {
        BudgetCategoryEntity existing = existingCategory();
        given(categoryRepository.findById(CATEGORY_ID)).willReturn(Optional.of(existing));
        given(categoryRepository.save(any(BudgetCategoryEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        UpdateCategoryRequest request = new UpdateCategoryRequest("新名称", null, "新説明");

        service.update(CATEGORY_ID, request, SCOPE_ID, SCOPE_TYPE);

        ArgumentCaptor<BudgetCategoryEntity> captor = ArgumentCaptor.forClass(BudgetCategoryEntity.class);
        verify(categoryRepository).save(captor.capture());
        assertThat(captor.getValue().getSortOrder()).isEqualTo(3);
        assertThat(captor.getValue().getId()).isEqualTo(CATEGORY_ID);
    }
}
