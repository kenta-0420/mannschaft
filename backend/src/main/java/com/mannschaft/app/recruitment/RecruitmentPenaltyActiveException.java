package com.mannschaft.app.recruitment;

import com.mannschaft.app.common.BusinessException;
import java.time.Instant;

/** 募集申込を阻むペナルティと、その解除予定時刻。 */
public class RecruitmentPenaltyActiveException extends BusinessException {

    private final Instant expiresAt;

    public RecruitmentPenaltyActiveException(Instant expiresAt) {
        super(RecruitmentErrorCode.PENALTY_ACTIVE);
        this.expiresAt = expiresAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
