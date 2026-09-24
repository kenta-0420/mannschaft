package com.mannschaft.app.payment.escrow;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** Stripe I/O 完了後に Payment Request の escrow を永続化する。 */
@Service
@RequiredArgsConstructor
public class PaymentRequestEscrowPersistenceService {

    private final EscrowTransactionRepository escrowTransactionRepository;
    private final PaymentRequestEscrowInsertService insertService;

    EscrowTransactionEntity persist(EscrowTransactionEntity escrow) {
        try {
            return insertService.insert(escrow);
        } catch (DataIntegrityViolationException e) {
            return insertService.findByIdempotencyKey(escrow.getStripeIdempotencyKey())
                    .orElseThrow(() -> e);
        }
    }
}
