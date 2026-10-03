package com.mannschaft.app.ranch;

import com.mannschaft.app.auth.entity.UserEntity.UserStatus;
import com.mannschaft.app.auth.entity.UserEntity;
import java.util.UUID;

/** 実ユーザー情報を用いず、無料・未所属の本人を作る。 */
final class RanchTestFixture {
    private RanchTestFixture() { }
    static UserEntity user() {
        return UserEntity.builder().email("ranch-" + UUID.randomUUID() + "@example.invalid")
                .lastName("試験").firstName("本人").displayName("牧場試験")
                .status(UserStatus.ACTIVE).isSearchable(false).locale("ja").timezone("Asia/Tokyo").build();
    }
}
