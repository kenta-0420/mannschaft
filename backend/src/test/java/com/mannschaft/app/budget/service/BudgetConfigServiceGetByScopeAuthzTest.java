package com.mannschaft.app.budget.service;

import com.mannschaft.app.budget.BudgetMapper;
import com.mannschaft.app.budget.entity.BudgetConfigEntity;
import com.mannschaft.app.budget.repository.BudgetConfigRepository;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

/**
 * {@link BudgetConfigService#getByScope} の認可単体テスト（CMP-260917-2102 Phase 1 の追撃）。
 *
 * <p>現状は TeamBudgetConfigController/OrgBudgetConfigController が呼び出し前に
 * checkAdminOrAbove で二重防御しているため実機での露出はないが、サービス層が
 * checkMembership のままでは将来ガード無しの呼び出し元が追加された際に穴になる
 * （共通ヘルパ一元化≠全経路使用の戒め）。予算は TeamSidebar/OrganizationSidebar とも
 * DEPUTY_ADMIN 限定のため、サービス層でも無条件 checkAdminOrAbove に揃える。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BudgetConfigService 認可単体テスト（getByScope）")
class BudgetConfigServiceGetByScopeAuthzTest {

    @Mock private BudgetConfigRepository configRepository;
    @Mock private BudgetMapper budgetMapper;
    @Mock private AccessControlService accessControlService;

    @InjectMocks
    private BudgetConfigService service;

    private static final Long SCOPE_ID = 9L;
    private static final String SCOPE_TYPE = "ORGANIZATION";
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

    @Test
    @DisplayName("ORGANIZATIONスコープ: MEMBERは403（COMMON_002）で拒否される")
    void member_isForbidden() {
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(accessControlService).checkAdminOrAbove(eq(ACTOR_ID), eq(SCOPE_ID), eq(SCOPE_TYPE));

        assertThatThrownBy(() -> service.getByScope(SCOPE_TYPE, SCOPE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);
    }

    @Test
    @DisplayName("ORGANIZATIONスコープ: ADMINは200相当で取得できる")
    void admin_isAllowed() {
        doNothing().when(accessControlService).checkAdminOrAbove(eq(ACTOR_ID), eq(SCOPE_ID), eq(SCOPE_TYPE));
        given(configRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                .willReturn(Optional.of(BudgetConfigEntity.builder()
                        .scopeType(SCOPE_TYPE)
                        .scopeId(SCOPE_ID)
                        .build()));

        assertThatCode(() -> service.getByScope(SCOPE_TYPE, SCOPE_ID)).doesNotThrowAnyException();
    }
}
