package com.mannschaft.app.loyalty;

import com.mannschaft.app.loyalty.dto.TransactionResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ポイント書込の実DB試練用入口。実装は先行試練のred確認後に追加する。 */
@Service
public class LoyaltyPointsWriter {
    @Transactional
    public TransactionResponse award(LoyaltyWriteCommand command) {
        return null;
    }
    @Transactional
    public TransactionResponse redeem(LoyaltyWriteCommand command) {
        return null;
    }

    @Transactional
    public LoyaltySubmissionOutcome submit(LoyaltyWriteCommand command) {
        return null;
    }

    @Transactional
    public LoyaltyRequestActionOutcome act(LoyaltyRequestActionCommand command) {
        return null;
    }
}
