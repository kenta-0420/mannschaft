package com.mannschaft.app.receipt;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.receipt.repository.ReceiptLineItemRepository;
import com.mannschaft.app.receipt.repository.ReceiptRepository;
import com.mannschaft.app.receipt.service.ReceiptMyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * {@link ReceiptMyService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReceiptMyService 単体テスト")
class ReceiptMyServiceTest {

    @Mock private ReceiptRepository receiptRepository;
    @Mock private ReceiptLineItemRepository lineItemRepository;
    @Mock private ReceiptPdfGenerator pdfGenerator;

    @InjectMocks
    private ReceiptMyService service;

    @Nested
    @DisplayName("getMyReceiptPdf")
    class GetMyReceiptPdf {

        @Test
        @DisplayName("異常系: 自分宛でない領収書はエラー")
        void 自分宛でない() {
            given(receiptRepository.findByIdAndRecipientUserId(1L, 100L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getMyReceiptPdf(100L, 1L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReceiptErrorCode.RECEIPT_NOT_FOUND);
        }
    }
}
