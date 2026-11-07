package com.mannschaft.app.loyalty;

import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;

import com.mannschaft.app.loyalty.entity.LoyaltyAwardRequestEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyProgramEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyRuleEntity;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.LocalDate;

/** 申請の保存終端と現在snapshotを区別し、設計§5.1の優先順で判定する。 */
@Component
public class LoyaltyRequestStatePolicy {
    public LoyaltyEffectiveRequestState evaluate(LoyaltyAwardRequestEntity request,
            LoyaltyProgramEntity program, LoyaltyRuleEntity rule, RequestReason externalFailure,
            boolean dailyAwarded, int balance, Instant now) {
        // 保存終端だけが不可逆。GETの現在snapshot判定はEntityへ書き戻さない。
        if (request.getStatus() != RequestStatus.PENDING) {
            return new LoyaltyEffectiveRequestState(request.getStatus(), request.getReason());
        }
        if (!request.getAwardOn().equals(LocalDate.ofInstant(now, UserZoneLocalDateTimeParser.SERVER_ZONE))) {
            return new LoyaltyEffectiveRequestState(RequestStatus.EXPIRED, RequestReason.DAY_CHANGED);
        }
        if (!now.isBefore(request.getExpiresAt())) {
            return new LoyaltyEffectiveRequestState(RequestStatus.EXPIRED, RequestReason.TTL_EXPIRED);
        }
        if (program == null || rule == null || !program.isEnabled() || !rule.isActive()
                || !rule.isSelfAwardEnabled() || program.getSelfAwardMode() != SelfAwardMode.STAFF_APPROVAL
                || program.getVersion() != request.getProgramVersion()
                || rule.getVersion() != request.getRuleVersion() || rule.getPoints() != request.getPoints()) {
            return new LoyaltyEffectiveRequestState(RequestStatus.INVALIDATED, RequestReason.VERSION_CHANGED);
        }
        if (externalFailure != null) {
            return new LoyaltyEffectiveRequestState(RequestStatus.INVALIDATED, externalFailure);
        }
        if (dailyAwarded) {
            return new LoyaltyEffectiveRequestState(RequestStatus.SUPERSEDED, RequestReason.DAILY_AWARDED);
        }
        if ((long) balance + request.getPoints() > Integer.MAX_VALUE) {
            return new LoyaltyEffectiveRequestState(RequestStatus.INVALIDATED, RequestReason.POINT_LIMIT);
        }
        return new LoyaltyEffectiveRequestState(RequestStatus.PENDING, null);
    }
}
