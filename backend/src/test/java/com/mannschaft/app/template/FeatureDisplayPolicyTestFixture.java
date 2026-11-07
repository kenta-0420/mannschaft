package com.mannschaft.app.template;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.team.entity.TeamEntity;

import java.util.UUID;

/** 表示設定試練が所有する最小の有効なスコープ・ユーザーfixture。 */
final class FeatureDisplayPolicyTestFixture {
    private FeatureDisplayPolicyTestFixture() { }

    static UserEntity user() {
        return UserEntity.builder().email("display-" + UUID.randomUUID() + "@example.test")
                .lastName("表示設定").firstName("試験").displayName("表示設定試験")
                .locale("ja").timezone("Asia/Tokyo").isSearchable(false).status(UserEntity.UserStatus.ACTIVE).build();
    }

    static TeamEntity team() {
        return TeamEntity.builder().name("表示設定試験").slug(slug())
                .visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE).supporterEnabled(true).build();
    }

    static OrganizationEntity organization() {
        return OrganizationEntity.builder().name("表示設定試験").slug(slug())
                .orgType(OrganizationEntity.OrgType.OTHER).visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(true).build();
    }

    private static String slug() {
        return "display-" + UUID.randomUUID().toString().substring(0, 20);
    }
}
