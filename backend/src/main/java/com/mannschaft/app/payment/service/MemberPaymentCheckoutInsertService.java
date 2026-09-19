package com.mannschaft.app.payment.service;

import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Connect checkout の支払い記録を独立トランザクションで確定する。 */
@Service
@RequiredArgsConstructor
class MemberPaymentCheckoutInsertService {

    private final MemberPaymentRepository memberPaymentRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    MemberPaymentEntity insert(MemberPaymentEntity payment) {
        return memberPaymentRepository.saveAndFlush(payment);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    java.util.Optional<MemberPaymentEntity> findByEscrowTransactionId(java.util.UUID escrowTransactionId) {
        return memberPaymentRepository.findByEscrowTransactionId(escrowTransactionId);
    }
}
