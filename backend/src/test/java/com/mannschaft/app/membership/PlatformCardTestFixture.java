package com.mannschaft.app.membership;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.membership.entity.MemberCardEntity;
import com.mannschaft.app.team.entity.TeamEntity;
import java.util.UUID;

/** 共通会員証契約で使う最小の永続化fixture。 */
final class PlatformCardTestFixture {
    private PlatformCardTestFixture() { }

    static UserEntity user() {
        return UserEntity.builder().email(UUID.randomUUID() + "@platform-contract.example")
                .lastName("契約").firstName("太郎").displayName("契約表示名")
                .isSearchable(false).locale("ja").timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE).build();
    }

    static TeamEntity team() {
        return TeamEntity.builder().name("受付契約チーム").slug("pc-" + UUID.randomUUID().toString().substring(0, 20))
                .visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(true).build();
    }

    static MemberCardEntity card(Long userId, ScopeType scopeType, Long scopeId, CardStatus status) {
        return MemberCardEntity.builder().userId(userId).scopeType(scopeType).scopeId(scopeId)
                .cardCode(UUID.randomUUID().toString()).cardNumber("P-" + userId).displayName("契約表示名")
                .qrSecret("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                .status(status).build();
    }
}
