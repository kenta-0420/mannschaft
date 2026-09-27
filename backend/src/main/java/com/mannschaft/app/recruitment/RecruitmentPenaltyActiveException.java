package com.mannschaft.app.recruitment;

import com.mannschaft.app.common.BusinessException;
import java.time.LocalDateTime;

/** 募集申込を阻むペナルティと、その解除予定時刻。 */
public class RecruitmentPenaltyActiveException extends BusinessException {

    private final LocalDateTime expiresAt;

    public RecruitmentPenaltyActiveException(LocalDateTime expiresAt) {
        super(RecruitmentErrorCode.PENALTY_ACTIVE);
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }
}
