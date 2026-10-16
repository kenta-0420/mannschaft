package com.mannschaft.app.loyalty;

import org.springframework.stereotype.Component;

/** 公開Serviceの現在snapshotだけで対象資格を判断する。全体ロールによる昇格は入力にも持たない。 */
@Component
public class LoyaltyEligibilityPolicy {
    public boolean isEligible(Audience audience, boolean activeUser, boolean activeMembership,
                              boolean regularMemberOrAbove) {
        if (audience == null || !activeUser) return false;
        return switch (audience) {
            case ALL_AUTHENTICATED -> true;
            case SUPPORTERS_AND_ABOVE -> activeMembership;
            case MEMBERS_AND_ABOVE -> activeMembership && regularMemberOrAbove;
        };
    }
}
