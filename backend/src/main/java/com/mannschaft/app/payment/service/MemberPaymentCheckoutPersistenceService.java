package com.mannschaft.app.payment.service;

import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** 同一 escrow の並行起票を既存支払い記録へ収束させる。 */
@Service
@RequiredArgsConstructor
public class MemberPaymentCheckoutPersistenceService {

    private final MemberPaymentRepository memberPaymentRepository;
    private final MemberPaymentCheckoutInsertService insertService;

    public MemberPaymentCheckoutRecord persist(MemberPaymentCheckoutRecord request) {
        MemberPaymentEntity payment = request.toEntity();
        UUID escrowTransactionId = payment.getEscrowTransactionId();
        try {
            return MemberPaymentCheckoutRecord.fromEntity(insertService.insert(payment));
        } catch (DataIntegrityViolationException e) {
            return insertService.findByEscrowTransactionId(escrowTransactionId)
                    .map(MemberPaymentCheckoutRecord::fromEntity)
                    .orElseThrow(() -> e);
        }
    }
}
