package com.mannschaft.app.payment;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.dto.ContentPaymentGateRequest;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.repository.ContentPaymentGateRepository;
import com.mannschaft.app.payment.service.ContentPaymentGateService;
import com.mannschaft.app.payment.service.PaymentItemService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * {@link ContentPaymentGateService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ContentPaymentGateService 単体テスト")
class ContentPaymentGateServiceTest {

    @Mock private ContentPaymentGateRepository contentPaymentGateRepository;
    @Mock private PaymentItemService paymentItemService;

    @InjectMocks
    private ContentPaymentGateService service;

    private static final Long TEAM_ID = 1L;

    @Nested
    @DisplayName("setTeamContentGates")
    class SetTeamContentGates {

        @Test
        @DisplayName("異常系: サポートされていないコンテンツ種別はエラー")
        void 不正コンテンツ種別() {
            ContentPaymentGateRequest request = new ContentPaymentGateRequest(
                    "INVALID_TYPE", 1L, List.of());

            assertThatThrownBy(() -> service.setTeamContentGates(TEAM_ID, 100L, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(PaymentErrorCode.UNSUPPORTED_CONTENT_TYPE);
        }
    }
}
