package com.mannschaft.app.payment.service;

import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class MemberPaymentCheckoutPersistenceServiceTest {

    @Test
    void unique競合時は既存の支払い記録を返す() {
        MemberPaymentRepository repository = mock(MemberPaymentRepository.class);
        MemberPaymentCheckoutInsertService insertService = mock(MemberPaymentCheckoutInsertService.class);
        MemberPaymentCheckoutPersistenceService service =
                new MemberPaymentCheckoutPersistenceService(repository, insertService);
        UUID escrowId = UUID.randomUUID();
        MemberPaymentCheckoutRecord candidate = new MemberPaymentCheckoutRecord(
                null, 1L, 2L, java.math.BigDecimal.ONE, "JPY", null, null, 1L, null, escrowId);
        MemberPaymentEntity existing = MemberPaymentEntity.builder().id(1L).escrowTransactionId(escrowId).build();
        given(insertService.insert(org.mockito.ArgumentMatchers.any())).willThrow(new DataIntegrityViolationException("duplicate"));
        given(insertService.findByEscrowTransactionId(escrowId)).willReturn(Optional.of(existing));

        assertThat(service.persist(candidate).id()).isEqualTo(1L);
    }
}
