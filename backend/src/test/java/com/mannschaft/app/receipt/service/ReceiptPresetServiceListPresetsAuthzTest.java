package com.mannschaft.app.receipt.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.receipt.ReceiptMapper;
import com.mannschaft.app.receipt.ReceiptScopeType;
import com.mannschaft.app.receipt.repository.ReceiptPresetRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

/**
 * {@link ReceiptPresetService#listPresets} の認可単体テスト（CMP-260917-2102 Phase 1 の追撃）。
 *
 * <p><b>スコープ差分あり</b>: 実機で ORGANIZATION の MEMBER がプリセット一覧
 * （{@code GET /api/v1/admin/receipt-presets?scopeType=ORGANIZATION}）を取得できることを
 * 確認したため、当初は無条件 checkAdminOrAbove に是正しようとしたが、既存 IT
 * {@code ReceiptAuthzContractTest}「AC-2-1c: teamAの非ADMINメンバーはプリセット一覧を
 * 閲覧できる → 200」が TEAM スコープでの MEMBER 閲覧可を意図的に固定した契約であることが
 * 判明した。よって {@code DirectMailService#listMails} と同型のスコープ分岐にする：
 * ORGANIZATION のみ ADMIN 必須、TEAM は checkMembership のまま維持する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReceiptPresetService 認可単体テスト（listPresets・スコープ差分）")
class ReceiptPresetServiceListPresetsAuthzTest {

    @Mock private ReceiptPresetRepository presetRepository;
    @Mock private ReceiptMapper receiptMapper;
    @Mock private AccessControlService accessControlService;

    @InjectMocks
    private ReceiptPresetService service;

    private static final Long SCOPE_ID = 9L;
    private static final Long ACTOR_ID = 100L;

    @Nested
    @DisplayName("ORGANIZATIONスコープ")
    class OrganizationScope {

        private static final ReceiptScopeType SCOPE_TYPE = ReceiptScopeType.ORGANIZATION;

        @Test
        @DisplayName("MEMBERは403（COMMON_002）で拒否される")
        void member_isForbidden() {
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(eq(ACTOR_ID), eq(SCOPE_ID), eq(SCOPE_TYPE.name()));

            assertThatThrownBy(() -> service.listPresets(SCOPE_TYPE, SCOPE_ID, ACTOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }

        @Test
        @DisplayName("ADMINは200相当で取得できる")
        void admin_isAllowed() {
            doNothing().when(accessControlService).checkAdminOrAbove(eq(ACTOR_ID), eq(SCOPE_ID), eq(SCOPE_TYPE.name()));
            given(presetRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(java.util.List.of());
            given(receiptMapper.toPresetResponseList(java.util.List.of())).willReturn(java.util.List.of());

            assertThatCode(() -> service.listPresets(SCOPE_TYPE, SCOPE_ID, ACTOR_ID)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("TEAMスコープ（ReceiptAuthzContractTestの既存契約を維持）")
    class TeamScope {

        private static final ReceiptScopeType SCOPE_TYPE = ReceiptScopeType.TEAM;

        @Test
        @DisplayName("MEMBERは200相当で取得できる（スコープ契約維持）")
        void member_isAllowed() {
            doNothing().when(accessControlService).checkMembership(eq(ACTOR_ID), eq(SCOPE_ID), eq(SCOPE_TYPE.name()));
            given(presetRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(java.util.List.of());
            given(receiptMapper.toPresetResponseList(java.util.List.of())).willReturn(java.util.List.of());

            assertThatCode(() -> service.listPresets(SCOPE_TYPE, SCOPE_ID, ACTOR_ID)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("非メンバーは403（COMMON_002）で拒否される")
        void nonMember_isForbidden() {
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkMembership(eq(ACTOR_ID), eq(SCOPE_ID), eq(SCOPE_TYPE.name()));

            assertThatThrownBy(() -> service.listPresets(SCOPE_TYPE, SCOPE_ID, ACTOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }
    }
}
