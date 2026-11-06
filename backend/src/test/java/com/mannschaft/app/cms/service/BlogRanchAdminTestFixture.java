package com.mannschaft.app.cms.service;

import com.mannschaft.app.auth.entity.UserEntity;

import java.util.UUID;

/** 実利用者情報を使わず、個人牧場未参加の現役管理主体を作る。保存・資格付与はIT側で行う。 */
final class BlogRanchAdminTestFixture {
    private BlogRanchAdminTestFixture() { }

    static UserEntity user() {
        return UserEntity.builder().email(UUID.randomUUID() + "@source-admin-fixture.invalid")
                .lastName("運営").firstName("検証").displayName("運営検証").isSearchable(false)
                .locale("ja").timezone("UTC").status(UserEntity.UserStatus.ACTIVE).build();
    }
}
