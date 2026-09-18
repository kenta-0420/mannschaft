package com.mannschaft.app.payment.escrow;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Payment Request の escrow INSERT を独立したトランザクションで確定する。 */
@Service
@RequiredArgsConstructor
public class PaymentRequestEscrowInsertService {

    private final EscrowTransactionRepository escrowTransactionRepository;

    @Transactional
    public EscrowTransactionEntity insert(EscrowTransactionEntity escrow) {
        return escrowTransactionRepository.saveAndFlush(escrow);
    }
}
