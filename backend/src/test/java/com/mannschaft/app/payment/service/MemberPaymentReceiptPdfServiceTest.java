package com.mannschaft.app.payment.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.payment.PaymentErrorCode;
import com.mannschaft.app.payment.PaymentStatus;
import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.repository.PaymentItemRepository;
import com.mannschaft.app.payment.repository.PaymentItemRepository.PaymentItemReceiptContext;
import com.mannschaft.app.receipt.dto.MemberPaymentReceiptDocument;
import com.mannschaft.app.receipt.service.MemberPaymentReceiptDocumentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MemberPaymentReceiptPdfServiceTest {
    @Mock private MemberPaymentRepository memberPaymentRepository;
    @Mock private PaymentItemRepository paymentItemRepository;
    @Mock private NameResolverService nameResolverService;
    @Mock private MemberPaymentReceiptDocumentService receiptDocumentService;
    @Mock private Clock clock;
    @InjectMocks private MemberPaymentReceiptPdfService service;

    @Test
    void generatePaidPaymentUsesPayerAndIssuer() {
        MemberPaymentEntity payment = payment(PaymentStatus.PAID, new BigDecimal("5000"));
        PaymentItemReceiptContext item = new PaymentItemReceiptContext() {
            @Override
            public String getName() {
                return "Annual fee";
            }

            @Override
            public Long getTeamId() {
                return 100L;
            }

            @Override
            public Long getOrganizationId() {
                return null;
            }
        };
        given(memberPaymentRepository.findById(1L)).willReturn(Optional.of(payment));
        given(paymentItemRepository.findReceiptContextById(200L)).willReturn(Optional.of(item));
        given(nameResolverService.resolveUserDisplayName(10L)).willReturn("Payer");
        given(receiptDocumentService.generate(any())).willReturn(new byte[] {1});

        byte[] result = service.generate(1L, 10L);

        ArgumentCaptor<MemberPaymentReceiptDocument> receipt =
                ArgumentCaptor.forClass(MemberPaymentReceiptDocument.class);
        verify(receiptDocumentService).generate(receipt.capture());
        assertThat(result).containsExactly(1);
        assertThat(receipt.getValue().recipientName()).isEqualTo("Payer");
        assertThat(receipt.getValue().scopeType()).isEqualTo("TEAM");
        assertThat(receipt.getValue().scopeId()).isEqualTo(100L);
    }

    @Test
    void generatePendingPaymentHidesRecord() {
        given(memberPaymentRepository.findById(1L)).willReturn(Optional.of(payment(PaymentStatus.PENDING, new BigDecimal("5000"))));
        assertThatThrownBy(() -> service.generate(1L, 10L)).isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.MEMBER_PAYMENT_NOT_FOUND));
        verify(receiptDocumentService, never()).generate(any());
    }

    @Test
    void generateOtherUsersPaymentHidesBeforeLoadingItem() {
        given(memberPaymentRepository.findById(1L))
                .willReturn(Optional.of(payment(PaymentStatus.PAID, new BigDecimal("5000"))));

        assertThatThrownBy(() -> service.generate(1L, 99L)).isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_ACCESS_DENIED));
        verify(paymentItemRepository, never()).findReceiptContextById(any());
        verify(receiptDocumentService, never()).generate(any());
    }

    private MemberPaymentEntity payment(PaymentStatus status, BigDecimal amount) {
        MemberPaymentEntity payment = MemberPaymentEntity.builder().userId(20L).payerUserId(10L)
                .paymentItemId(200L).amountPaid(amount).status(status)
                .paidAt(LocalDateTime.of(2026, 9, 20, 12, 0)).build();
        ReflectionTestUtils.setField(payment, "id", 1L);
        return payment;
    }
}
