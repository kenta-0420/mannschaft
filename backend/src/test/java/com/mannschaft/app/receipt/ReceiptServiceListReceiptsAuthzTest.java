package com.mannschaft.app.receipt;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.receipt.repository.ReceiptIssuerSettingsRepository;
import com.mannschaft.app.receipt.repository.ReceiptLineItemRepository;
import com.mannschaft.app.receipt.repository.ReceiptRepository;
import com.mannschaft.app.receipt.service.ReceiptPdfArchiveService;
import com.mannschaft.app.receipt.service.ReceiptService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

/**
 * {@link ReceiptService#listReceipts}（{@code /api/v1/admin/receipts} 用）の
 * 認可単体テスト（CMP-260917-1350 Phase 1）。
 *
 * <p>組織サイドバーで ADMIN/DEPUTY_ADMIN 限定表示している「領収書」一覧の GET が
 * {@code checkMembership} 止まりで MEMBER も閲覧できていた認可漏れを根治する。
 * メンバー本人用の {@code /api/v1/my/receipts}（{@code ReceiptMyController} /
 * {@code ReceiptMyService}）は対象外であり、このテストでは触らない。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReceiptService 認可単体テスト（listReceipts）")
class ReceiptServiceListReceiptsAuthzTest {

    @Mock private ReceiptRepository receiptRepository;
    @Mock private ReceiptLineItemRepository lineItemRepository;
    @Mock private ReceiptIssuerSettingsRepository issuerSettingsRepository;
    @Mock private ReceiptMapper receiptMapper;
    @Mock private ReceiptPdfGenerator pdfGenerator;
    @Mock private NameResolverService nameResolverService;
    @Mock private MemberPaymentRepository memberPaymentRepository;
    @Mock private AccessControlService accessControlService;
    @Mock private ReceiptPdfArchiveService pdfArchiveService;

    @InjectMocks
    private ReceiptService service;

    private static final Long SCOPE_ID = 1L;
    private static final Long ACTOR_ID = 100L;
    private static final ReceiptScopeType SCOPE_TYPE = ReceiptScopeType.TEAM;

    @Test
    @DisplayName("MEMBER は 403（COMMON_002）で拒否される")
    void member_isForbidden() {
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(accessControlService).checkAdminOrAbove(anyLong(), eq(SCOPE_ID), eq(SCOPE_TYPE.name()));

        assertThatThrownBy(() -> service.listReceipts(SCOPE_TYPE, SCOPE_ID, 0, 20, ACTOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);
    }

    @Test
    @DisplayName("ADMIN は 200 相当で取得できる")
    void admin_isAllowed() {
        doNothing().when(accessControlService).checkAdminOrAbove(anyLong(), eq(SCOPE_ID), eq(SCOPE_TYPE.name()));
        given(receiptRepository.findByScopeTypeAndScopeIdOrderByIssuedAtDesc(eq(SCOPE_TYPE), eq(SCOPE_ID), any()))
                .willReturn(Page.empty(PageRequest.of(0, 20)));
        given(receiptMapper.toReceiptSummaryResponseList(any())).willReturn(java.util.List.of());

        assertThatCode(() -> service.listReceipts(SCOPE_TYPE, SCOPE_ID, 0, 20, ACTOR_ID))
                .doesNotThrowAnyException();
    }
}
