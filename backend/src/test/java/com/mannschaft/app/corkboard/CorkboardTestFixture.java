package com.mannschaft.app.corkboard;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.corkboard.entity.CorkboardCardEntity;
import com.mannschaft.app.corkboard.entity.CorkboardEntity;
import com.mannschaft.app.corkboard.entity.CorkboardGroupEntity;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.team.entity.TeamEntity;

import java.util.UUID;

/**
 * corkboard ドメインの試験データ（TestFixture 方式。保存は各 Repository 経由で行う）。
 */
final class CorkboardTestFixture {

    private CorkboardTestFixture() { }

    static UserEntity user(String label) {
        return UserEntity.builder()
                .email("cb-" + label + "-" + UUID.randomUUID() + "@example.com")
                .lastName("コルク").firstName("試験").displayName("コルク契約 " + label)
                .isSearchable(false)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo")
                .build();
    }

    static TeamEntity team(String name) {
        return TeamEntity.builder()
                .slug("cb-" + UUID.randomUUID().toString().substring(0, 20))
                .name(name)
                .template("sports")
                .visibility(TeamEntity.Visibility.PUBLIC)
                .supporterEnabled(false)
                .build();
    }

    static OrganizationEntity organization(String name) {
        return OrganizationEntity.builder()
                .slug("cb-o-" + UUID.randomUUID().toString().substring(0, 20))
                .name(name)
                .orgType(OrganizationEntity.OrgType.OTHER)
                .visibility(OrganizationEntity.Visibility.PRIVATE)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(false)
                .build();
    }

    static CorkboardEntity scopedBoard(String scopeType, Long scopeId, String editPolicy) {
        return CorkboardEntity.builder()
                .scopeType(scopeType).scopeId(scopeId)
                .name("コルク契約ボード " + UUID.randomUUID())
                .editPolicy(editPolicy)
                .build();
    }

    static CorkboardEntity personalBoard(Long ownerId) {
        return CorkboardEntity.builder()
                .scopeType("PERSONAL").ownerId(ownerId)
                .name("コルク契約個人ボード " + UUID.randomUUID())
                .build();
    }

    static CorkboardCardEntity memoCard(Long boardId, Long createdBy) {
        return CorkboardCardEntity.builder()
                .corkboardId(boardId).cardType("MEMO").title("コルク契約メモ").createdBy(createdBy)
                .build();
    }

    static CorkboardGroupEntity group(Long boardId) {
        return CorkboardGroupEntity.builder()
                .corkboardId(boardId).name("コルク契約セクション")
                .build();
    }
}
