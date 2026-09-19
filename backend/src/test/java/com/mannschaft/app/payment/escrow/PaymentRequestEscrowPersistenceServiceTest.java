package com.mannschaft.app.payment.escrow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class PaymentRequestEscrowPersistenceServiceTest {

    @Mock
    private EscrowTransactionRepository repository;

    @Mock
    private PaymentRequestEscrowInsertService insertService;

    @Test
    void 一意制約競合はrollback完了後に既存escrowへ収束する() {
        EscrowTransactionEntity candidate = EscrowTransactionEntity.builder()
                .stripeIdempotencyKey("prpay-attempt")
                .build();
        EscrowTransactionEntity existing = EscrowTransactionEntity.builder()
                .stripeIdempotencyKey("prpay-attempt")
                .build();
        given(insertService.insert(candidate)).willThrow(new DataIntegrityViolationException("duplicate"));
        given(repository.findByStripeIdempotencyKey("prpay-attempt")).willReturn(Optional.of(existing));

        PaymentRequestEscrowPersistenceService service =
                new PaymentRequestEscrowPersistenceService(repository, insertService);

        assertThat(service.persist(candidate)).isSameAs(existing);
    }
}
