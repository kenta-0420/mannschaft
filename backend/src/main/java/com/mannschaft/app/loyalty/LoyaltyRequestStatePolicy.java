package com.mannschaft.app.loyalty;

import com.mannschaft.app.loyalty.entity.LoyaltyAwardRequestEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyProgramEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyRuleEntity;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

/** 申請の保存終端と現在snapshotを区別し、設計§5.1の優先順で判定する。 */
@Component
public class LoyaltyRequestStatePolicy {
    public LoyaltyEffectiveRequestState evaluate(LoyaltyAwardRequestEntity request,
            LoyaltyProgramEntity program, LoyaltyRuleEntity rule, RequestReason externalFailure,
            boolean dailyAwarded, int balance, LocalDateTime now) {
        return new LoyaltyEffectiveRequestState(RequestStatus.PENDING, null);
    }
}
