package com.mannschaft.app.receipt;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.receipt.dto.BulkCreateReceiptRequest;
import com.mannschaft.app.receipt.dto.BulkVoidReceiptRequest;
import com.mannschaft.app.receipt.dto.CreateReceiptRequest;
import com.mannschaft.app.receipt.dto.ReceiptResponse;
import com.mannschaft.app.receipt.dto.VoidReceiptRequest;
import com.mannschaft.app.receipt.entity.ReceiptEntity;
import com.mannschaft.app.receipt.entity.ReceiptIssuerSettingsEntity;
import com.mannschaft.app.receipt.repository.ReceiptIssuerSettingsRepository;
import com.mannschaft.app.receipt.repository.ReceiptLineItemRepository;
import com.mannschaft.app.receipt.repository.ReceiptRepository;
import com.mannschaft.app.receipt.service.ReceiptService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * {@link ReceiptService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReceiptService 単体テスト")
class ReceiptServiceTest {

    @Mock private ReceiptRepository receiptRepository;
    @Mock private ReceiptLineItemRepository lineItemRepository;
    @Mock private ReceiptIssuerSettingsRepository issuerSettingsRepository;
    @Mock private ReceiptMapper receiptMapper;
    @Mock private ReceiptPdfGenerator pdfGenerator;
    @Mock private NameResolverService nameResolverService;
    @Mock private MemberPaymentRepository memberPaymentRepository;

    @InjectMocks
    private ReceiptService service;

    private static final Long SCOPE_ID = 1L;
    private static final Long RECEIPT_ID = 10L;
    private static final Long USER_ID = 100L;
    private static final ReceiptScopeType SCOPE_TYPE = ReceiptScopeType.TEAM;

    @Nested
    @DisplayName("createReceipt")
    class CreateReceipt {

        @Test
        @DisplayName("異常系: 発行者設定が未登録の場合エラー")
        void 発行者設定未登録() {
            given(issuerSettingsRepository.findByScopeTypeAndScopeIdForUpdate(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            CreateReceiptRequest request = new CreateReceiptRequest(
                    null, null, null, null, "テスト太郎", null, null,
                    "テスト", new BigDecimal("10000"), null, null, null, null, null, null, null);

            assertThatThrownBy(() -> service.createReceipt(SCOPE_TYPE, SCOPE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReceiptErrorCode.ISSUER_SETTINGS_NOT_CONFIGURED);
        }

        @Test
        @DisplayName("異常系: 受領者名なし且つ受領者ユーザーIDもなしの場合エラー")
        void 受領者名必須() {
            ReceiptIssuerSettingsEntity settings = ReceiptIssuerSettingsEntity.builder()
                    .issuerName("テスト組織").receiptNumberPrefix("R-").nextReceiptNumber(1).build();
            given(issuerSettingsRepository.findByScopeTypeAndScopeIdForUpdate(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(settings));

            CreateReceiptRequest request = new CreateReceiptRequest(
                    null, null, null, null, null, null, null,
                    "テスト", new BigDecimal("10000"), null, null, null, null, null, null, null);

            assertThatThrownBy(() -> service.createReceipt(SCOPE_TYPE, SCOPE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReceiptErrorCode.RECIPIENT_NAME_REQUIRED);
        }
    }

    @Nested
    @DisplayName("voidReceipt")
    class VoidReceipt {

        @Test
        @DisplayName("異常系: 既に無効化済みの場合エラー")
        void 既に無効化済み() {
            ReceiptEntity receipt = ReceiptEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).status(ReceiptStatus.ISSUED)
                    .amount(new BigDecimal("10000")).build();
            // Simulate voided state
            setVoidedAt(receipt);
            given(receiptRepository.findByIdAndScopeTypeAndScopeId(RECEIPT_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(receipt));

            VoidReceiptRequest request = new VoidReceiptRequest("テスト理由");

            assertThatThrownBy(() -> service.voidReceipt(SCOPE_TYPE, SCOPE_ID, RECEIPT_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReceiptErrorCode.ALREADY_VOIDED);
        }
    }

    @Nested
    @DisplayName("approveReceipt")
    class ApproveReceipt {

        @Test
        @DisplayName("異常系: 下書きでない領収書の承認はエラー")
        void 下書きでない() {
            ReceiptEntity receipt = ReceiptEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).status(ReceiptStatus.ISSUED)
                    .amount(new BigDecimal("10000")).build();
            given(receiptRepository.findByIdAndScopeTypeAndScopeId(RECEIPT_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(receipt));

            assertThatThrownBy(() -> service.approveReceipt(SCOPE_TYPE, SCOPE_ID, RECEIPT_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReceiptErrorCode.NOT_DRAFT);
        }
    }

    @Nested
    @DisplayName("bulkCreateReceipts")
    class BulkCreateReceipts {

        @Test
        @DisplayName("異常系: 一括操作の上限（50件）超過")
        void 上限超過() {
            List<Long> ids = new java.util.ArrayList<>(Collections.nCopies(51, 1L));
            BulkCreateReceiptRequest request = new BulkCreateReceiptRequest(ids, null, null, null);

            assertThatThrownBy(() -> service.bulkCreateReceipts(SCOPE_TYPE, SCOPE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReceiptErrorCode.BULK_LIMIT_EXCEEDED);
        }
    }

    @Nested
    @DisplayName("bulkVoidReceipts")
    class BulkVoidReceipts {

        @Test
        @DisplayName("異常系: 一括無効化の上限（50件）超過")
        void 上限超過() {
            List<Long> ids = new java.util.ArrayList<>(Collections.nCopies(51, 1L));
            BulkVoidReceiptRequest request = new BulkVoidReceiptRequest(ids, "テスト");

            assertThatThrownBy(() -> service.bulkVoidReceipts(SCOPE_TYPE, SCOPE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReceiptErrorCode.BULK_LIMIT_EXCEEDED);
        }
    }

    private void setVoidedAt(ReceiptEntity entity) {
        try {
            var field = ReceiptEntity.class.getDeclaredField("voidedAt");
            field.setAccessible(true);
            field.set(entity, java.time.LocalDateTime.now());
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
