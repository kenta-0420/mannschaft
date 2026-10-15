package com.mannschaft.app.payment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.PaymentRequestPaymentAttemptStatus;
import com.mannschaft.app.payment.PaymentRequestStatus;
import com.mannschaft.app.payment.connect.ConnectAccountEntity;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.entity.PaymentRequestEntity;
import com.mannschaft.app.payment.entity.PaymentRequestPaymentAttemptEntity;
import com.mannschaft.app.payment.repository.PaymentRequestPaymentAttemptRepository;
import com.mannschaft.app.payment.repository.PaymentRequestRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentRequestPaymentTransactionServiceTest {

    private static final UUID REQUEST_ID = UUID.fromString("018f7f32-42a0-7cc0-8e9e-f6e9db5ee2c2");
    private static final UUID ATTEMPT_ID = UUID.fromString("018f7f32-42a0-7cc0-8e9e-f6e9db5ee2c3");
    private static final UUID PAYEE_ID = UUID.fromString("018f7f32-42a0-7cc0-8e9e-f6e9db5ee2c4");

    @Mock
    private PaymentRequestRepository paymentRequestRepository;

    @Mock
    private PaymentRequestPaymentAttemptRepository attemptRepository;

    @Mock
    private ConnectAccountRepository connectAccountRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private PaymentRequestEntity request;

    @Mock
    private PaymentRequestPaymentAttemptEntity attempt;

    @Test
    void 同一IdempotencyKeyは既存attemptへ収束してStripe再作成を要求しない() {
        prepareExistingAttemptRequest();
        given(attemptRepository.findByPaymentRequestIdAndClientKeyHash(REQUEST_ID,
                "8eb943e7040b69a94bf39562088223755bff4c2e7c5fc257f1e08f870fe01d35"))
                .willReturn(Optional.of(attempt));
        given(attempt.getId()).willReturn(ATTEMPT_ID);
        given(attempt.getPayerUserId()).willReturn(7L);
        given(attempt.getStripeIdempotencyKey()).willReturn("prpay-" + ATTEMPT_ID);
        given(attempt.getStatus()).willReturn(PaymentRequestPaymentAttemptStatus.REQUIRES_ACTION);
        given(attempt.getStripePaymentIntentId()).willReturn("pi_existing");
        given(attempt.getEscrowTransactionId()).willReturn(null);

        PaymentRequestPaymentTransactionService.PreparedPayment result = service()
                .prepare(2L, REQUEST_ID, 7L, "client-key");

        assertThat(result.attemptId()).isEqualTo(ATTEMPT_ID);
        assertThat(result.attemptStatus()).isEqualTo(PaymentRequestPaymentAttemptStatus.REQUIRES_ACTION);
        verify(attemptRepository, never()).save(any());
        verify(paymentRequestRepository, never()).save(request);
    }

    @Test
    void 処理中に異なるIdempotencyKeyは新attemptを作らず拒否する() {
        prepareProcessingRequest();
        given(request.getStatus()).willReturn(PaymentRequestStatus.PROCESSING);
        given(attemptRepository.findByPaymentRequestIdAndClientKeyHash(REQUEST_ID,
                "580843d03d2216ff1a275d0991bad66e4d1af871171d929e9de604b7959f9bca"))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service().prepare(2L, REQUEST_ID, 7L, "other-key"))
                .isInstanceOf(BusinessException.class);

        verify(attemptRepository, never()).save(any());
        verify(paymentRequestRepository, never()).save(request);
    }

    @Test
    void 失敗済みattemptと同じIdempotencyKeyは再利用せず拒否する() {
        prepareAuthorizedRequest();
        given(attemptRepository.findByPaymentRequestIdAndClientKeyHash(REQUEST_ID,
                "8eb943e7040b69a94bf39562088223755bff4c2e7c5fc257f1e08f870fe01d35"))
                .willReturn(Optional.of(attempt));
        given(attempt.getStatus()).willReturn(PaymentRequestPaymentAttemptStatus.FAILED);

        assertThatThrownBy(() -> service().prepare(2L, REQUEST_ID, 7L, "client-key"))
                .isInstanceOf(BusinessException.class);

        verify(attemptRepository, never()).save(any());
        verify(paymentRequestRepository, never()).save(request);
    }

    private void prepareExistingAttemptRequest() {
        prepareAuthorizedRequest();
        given(request.getPayeeConnectAccountId()).willReturn(PAYEE_ID);
        given(request.getId()).willReturn(REQUEST_ID);
        given(request.getOrganizationId()).willReturn(1L);
        given(request.getFaceAmount()).willReturn(3000);
        given(request.getCurrency()).willReturn("JPY");
    }

    private void prepareProcessingRequest() {
        prepareAuthorizedRequest();
        given(request.getPayeeConnectAccountId()).willReturn(PAYEE_ID);
        ConnectAccountEntity payee = ConnectAccountEntity.builder().payoutsEnabled(true).build();
        payee.setId(PAYEE_ID);
        given(connectAccountRepository.findById(PAYEE_ID)).willReturn(Optional.of(payee));
    }

    private void prepareAuthorizedRequest() {
        given(paymentRequestRepository.findByIdAndDeletedAtIsNullForUpdate(REQUEST_ID)).willReturn(Optional.of(request));
        given(request.getPayerScopeKind()).willReturn(ScopeKind.TEAM);
        given(request.getPayerScopeId()).willReturn(2L);
    }

    private PaymentRequestPaymentTransactionService service() {
        return new PaymentRequestPaymentTransactionService(
                paymentRequestRepository,
                attemptRepository,
                connectAccountRepository,
                accessControlService);
    }
}
