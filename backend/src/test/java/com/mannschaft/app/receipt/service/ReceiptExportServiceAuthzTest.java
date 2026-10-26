package com.mannschaft.app.receipt.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.receipt.ReceiptScopeType;
import com.mannschaft.app.receipt.dto.DownloadZipRequest;
import com.mannschaft.app.receipt.repository.ReceiptRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

/**
 * {@link ReceiptExportService} 閲覧系（exportCsv / createZipJob / getDescriptionSuggestions）
 * の認可単体テスト（CMP-260917-2102 Phase 1 の追撃）。
 *
 * <p>領収書エクスポートは予算CSVと同型の一括漏洩になりうる経路。実機で
 * {@code GET /api/v1/admin/receipts/export} を含む閲覧系4メソッドが checkMembership 止まりで
 * ORGANIZATION の MEMBER にも開いていることを想定し、領収書は TeamSidebar/OrganizationSidebar
 * とも DEPUTY_ADMIN 限定であるため無条件 checkAdminOrAbove に是正する。
 * {@code getZipJob} はジョブ作成時に固定したスコープを使うため {@link ReceiptExportServiceTest}
 * の既存テスト（ジョブ不存在系）と重複しない別クラスにした。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReceiptExportService 認可単体テスト（閲覧系）")
class ReceiptExportServiceAuthzTest {

    @Mock private ReceiptRepository receiptRepository;
    @Mock private AccessControlService accessControlService;

    @InjectMocks
    private ReceiptExportService service;

    private static final Long SCOPE_ID = 9L;
    private static final ReceiptScopeType SCOPE_TYPE = ReceiptScopeType.ORGANIZATION;
    private static final Long ACTOR_ID = 100L;

    @Nested
    @DisplayName("exportCsv")
    class ExportCsv {

        @Test
        @DisplayName("ORGANIZATIONスコープ: MEMBERは403（COMMON_002）で拒否される")
        void member_isForbidden() {
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(eq(ACTOR_ID), eq(SCOPE_ID), eq(SCOPE_TYPE.name()));

            assertThatThrownBy(() -> service.exportCsv(SCOPE_TYPE, SCOPE_ID, null, null, null, false, ACTOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }

        @Test
        @DisplayName("ORGANIZATIONスコープ: ADMINは200相当で取得できる")
        void admin_isAllowed() {
            doNothing().when(accessControlService).checkAdminOrAbove(eq(ACTOR_ID), eq(SCOPE_ID), eq(SCOPE_TYPE.name()));
            given(receiptRepository.findAll(org.mockito.ArgumentMatchers.<org.springframework.data.jpa.domain.Specification<com.mannschaft.app.receipt.entity.ReceiptEntity>>any()))
                    .willReturn(java.util.List.of());

            assertThatCode(() -> service.exportCsv(SCOPE_TYPE, SCOPE_ID, null, null, null, false, ACTOR_ID))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("createZipJob")
    class CreateZipJob {

        @Test
        @DisplayName("ORGANIZATIONスコープ: MEMBERは403（COMMON_002）で拒否される")
        void member_isForbidden() {
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(eq(ACTOR_ID), eq(SCOPE_ID), eq(SCOPE_TYPE.name()));

            DownloadZipRequest request = new DownloadZipRequest("ORGANIZATION", SCOPE_ID, null, null);

            assertThatThrownBy(() -> service.createZipJob(request, ACTOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }

        @Test
        @DisplayName("ORGANIZATIONスコープ: ADMINは200相当で取得できる")
        void admin_isAllowed() {
            doNothing().when(accessControlService).checkAdminOrAbove(eq(ACTOR_ID), eq(SCOPE_ID), eq(SCOPE_TYPE.name()));
            given(receiptRepository.count(any(org.springframework.data.jpa.domain.Specification.class))).willReturn(0L);

            DownloadZipRequest request = new DownloadZipRequest("ORGANIZATION", SCOPE_ID, null, null);

            assertThatCode(() -> service.createZipJob(request, ACTOR_ID)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("getDescriptionSuggestions")
    class GetDescriptionSuggestions {

        @Test
        @DisplayName("ORGANIZATIONスコープ: MEMBERは403（COMMON_002）で拒否される")
        void member_isForbidden() {
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(eq(ACTOR_ID), eq(SCOPE_ID), eq(SCOPE_TYPE.name()));

            assertThatThrownBy(() -> service.getDescriptionSuggestions(SCOPE_TYPE, SCOPE_ID, null, ACTOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }

        @Test
        @DisplayName("ORGANIZATIONスコープ: ADMINは200相当で取得できる")
        void admin_isAllowed() {
            doNothing().when(accessControlService).checkAdminOrAbove(eq(ACTOR_ID), eq(SCOPE_ID), eq(SCOPE_TYPE.name()));

            assertThatCode(() -> service.getDescriptionSuggestions(SCOPE_TYPE, SCOPE_ID, null, ACTOR_ID))
                    .doesNotThrowAnyException();
        }
    }
}
