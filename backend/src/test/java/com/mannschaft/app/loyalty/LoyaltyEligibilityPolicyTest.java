package com.mannschaft.app.loyalty;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;

/** AC04の現在資格。純粋判定の試練であり、公開Service集合照会/ScopeContractITの代替ではない。 */
class LoyaltyEligibilityPolicyTest {
    private final LoyaltyEligibilityPolicy policy = new LoyaltyEligibilityPolicy();

    @ParameterizedTest
    @CsvSource({
            "ALL_AUTHENTICATED,true,false,false,true",
            "ALL_AUTHENTICATED,true,true,false,true",
            "ALL_AUTHENTICATED,true,false,true,true",
            "ALL_AUTHENTICATED,false,true,true,false",
            "SUPPORTERS_AND_ABOVE,true,false,false,false",
            "SUPPORTERS_AND_ABOVE,true,true,false,true",
            "SUPPORTERS_AND_ABOVE,true,true,true,true",
            "SUPPORTERS_AND_ABOVE,false,true,true,false",
            "SUPPORTERS_AND_ABOVE,true,false,true,false",
            "MEMBERS_AND_ABOVE,true,false,false,false",
            "MEMBERS_AND_ABOVE,true,true,false,false",
            "MEMBERS_AND_ABOVE,true,true,true,true",
            "MEMBERS_AND_ABOVE,false,true,true,false",
            "MEMBERS_AND_ABOVE,true,false,true,false"})
    void AC04_資格とactive個人と正式在籍を同時に満たす(Audience audience, boolean activeUser,
            boolean activeMembership, boolean regularMemberOrAbove, boolean expected) {
        assertThat(policy.isEligible(audience, activeUser, activeMembership, regularMemberOrAbove)).isEqualTo(expected);
    }

    @Test
    void AC04_設定snapshot欠落はfailClosedで在籍のない全体管理者を昇格しない() {
        assertThat(policy.isEligible(null, true, true, true)).isFalse();
        assertThat(policy.isEligible(Audience.SUPPORTERS_AND_ABOVE, true, false, false)).isFalse();
        assertThat(policy.isEligible(Audience.MEMBERS_AND_ABOVE, true, false, false)).isFalse();
    }
}
