package com.mannschaft.app.safetycheck;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.safetycheck.dto.BulkRespondRequest;
import com.mannschaft.app.safetycheck.entity.SafetyCheckEntity;
import com.mannschaft.app.safetycheck.repository.SafetyCheckRepository;
import com.mannschaft.app.safetycheck.repository.SafetyResponseFollowupRepository;
import com.mannschaft.app.safetycheck.repository.SafetyResponseRepository;
import com.mannschaft.app.safetycheck.service.SafetyResponseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link SafetyResponseService} の認可契約テスト（束3 AC-1-4）。
 *
 * <p>安否確認一括回答（bulkRespond）は生命安全に直結するため、非ADMIN/DEPUTY_ADMINが
 * 他人を「安全」と偽装できてはならない。操作者ID（operatorUserId）を用いて
 * {@link AccessControlService#checkAdminOrAbove} でスコープADMIN以上を要求する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SafetyResponseService 認可契約テスト（AC-1-4）")
class SafetyResponseServiceAuthzTest {

    @Mock
    private SafetyCheckRepository safetyCheckRepository;

    @Mock
    private SafetyResponseRepository responseRepository;

    @Mock
    private SafetyResponseFollowupRepository followupRepository;

    @Mock
    private SafetyCheckMapper mapper;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private SafetyResponseService safetyResponseService;

    private static final Long SAFETY_CHECK_ID = 100L;
    private static final Long SCOPE_ID = 1L;
    private static final Long OPERATOR_USER_ID = 999L;

    private SafetyCheckEntity createActiveCheck() {
        return SafetyCheckEntity.builder()
                .scopeType(SafetyCheckScopeType.TEAM)
                .scopeId(SCOPE_ID)
                .title("地震発生")
                .status(SafetyCheckStatus.ACTIVE)
                .totalTargetCount(10)
                .createdBy(1L)
                .build();
    }

    // AC-1-4: 非ADMINメンバーが他人を「安全」と偽装するbulkRespond → 403（COMMON_002）
    @Test
    @DisplayName("一括回答_非ADMIN操作者_403でリポジトリ非委譲")
    void 一括回答_非ADMIN操作者_403() {
        // Given
        SafetyCheckEntity check = createActiveCheck();
        BulkRespondRequest.BulkRespondItem item = new BulkRespondRequest.BulkRespondItem(
                20L, "SAFE", "無事です（偽装）");
        BulkRespondRequest req = new BulkRespondRequest(List.of(item));

        given(safetyCheckRepository.findById(SAFETY_CHECK_ID)).willReturn(Optional.of(check));
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService).checkAdminOrAbove(OPERATOR_USER_ID, SCOPE_ID, "TEAM");

        // When & Then
        assertThatThrownBy(() -> safetyResponseService.bulkRespond(SAFETY_CHECK_ID, req, OPERATOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                        .isEqualTo("COMMON_002"));

        // 認可拒否時は回答保存に委譲されない（他人の安否偽装が成立しないことの裏取り）
        verify(responseRepository, never()).save(any());
        verify(responseRepository, never()).findBySafetyCheckIdAndUserId(any(), any());
    }

    // 非回帰: スコープADMINは従来通り一括回答が成立する
    @Test
    @DisplayName("一括回答_ADMIN操作者_成功_非回帰")
    void 一括回答_ADMIN操作者_成功() {
        // Given
        SafetyCheckEntity check = createActiveCheck();
        BulkRespondRequest.BulkRespondItem item = new BulkRespondRequest.BulkRespondItem(
                20L, "SAFE", "無事です");
        BulkRespondRequest req = new BulkRespondRequest(List.of(item));

        given(safetyCheckRepository.findById(SAFETY_CHECK_ID)).willReturn(Optional.of(check));
        given(responseRepository.findBySafetyCheckIdAndUserId(SAFETY_CHECK_ID, 20L))
                .willReturn(Optional.empty());

        // When
        List<?> result = safetyResponseService.bulkRespond(SAFETY_CHECK_ID, req, OPERATOR_USER_ID);

        // Then
        verify(accessControlService).checkAdminOrAbove(OPERATOR_USER_ID, SCOPE_ID, "TEAM");
        assertThat(result).hasSize(1);
    }
}
