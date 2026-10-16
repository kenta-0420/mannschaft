package com.mannschaft.app.loyalty;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyProgramEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyRuleEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyRewardEntity;
import java.time.LocalDateTime;
import java.util.UUID;

/** 各試練が所有する最小の有効fixtureを作る。 */
final class LoyaltyTestFixture {
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 16, 12, 0);
    private LoyaltyTestFixture() { }

    static UserEntity user() {
        return UserEntity.builder().email("loyalty-" + UUID.randomUUID() + "@example.test")
                .lastName("試験").firstName("利用者").displayName("ポイント本人")
                .isSearchable(false).locale("ja").timezone("Asia/Tokyo").status(UserEntity.UserStatus.ACTIVE).build();
    }

    static LoyaltyProgramEntity program(Long teamId) {
        return LoyaltyProgramEntity.builder().teamId(teamId).isEnabled(true).targetAudience(Audience.ALL_AUTHENTICATED)
                .selfAwardMode(SelfAwardMode.STAFF_APPROVAL).createdAt(CREATED_AT).updatedAt(CREATED_AT).build();
    }

    static LoyaltyRuleEntity rule(Long teamId) {
        return LoyaltyRuleEntity.builder().teamId(teamId).name("来店").points(1).frequency(Frequency.ONCE_PER_JST_DAY)
                .isSelfAwardEnabled(true).isActive(true).createdAt(CREATED_AT).updatedAt(CREATED_AT).build();
    }

    static LoyaltyRewardEntity reward(Long teamId, int requiredPoints) {
        return LoyaltyRewardEntity.builder().teamId(teamId).name("無料サービス").requiredPoints(requiredPoints)
                .isActive(true).createdAt(CREATED_AT).updatedAt(CREATED_AT).build();
    }
}
