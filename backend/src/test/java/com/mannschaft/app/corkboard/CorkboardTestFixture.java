package com.mannschaft.app.corkboard;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.common.visibility.RolePriority;
import com.mannschaft.app.corkboard.entity.CorkboardCardEntity;
import com.mannschaft.app.corkboard.entity.CorkboardEntity;
import com.mannschaft.app.corkboard.entity.CorkboardGroupEntity;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
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

    /** アクティブな所属（memberships 行）。所属は memberships で表現する（user_roles に MEMBER を張らない）。 */
    static MembershipEntity membership(Long userId, ScopeType scopeType, Long scopeId) {
        return MembershipEntity.builder()
                .userId(userId).scopeType(scopeType).scopeId(scopeId).roleKind(RoleKind.MEMBER)
                .build();
    }

    /** 権限ロール割当（user_roles 行）。チーム権限なら organizationId は null。 */
    static UserRoleEntity userRole(Long userId, Long roleId, Long teamId, Long organizationId) {
        return UserRoleEntity.builder()
                .userId(userId).roleId(roleId).teamId(teamId).organizationId(organizationId)
                .build();
    }

    /**
     * roles 行（統合テストは Flyway 無効で seed が無いため、未投入時だけ保存する）。
     * priority は本番 seed と同じ正準表 {@link RolePriority} から採る。
     */
    static RoleEntity role(String name) {
        return RoleEntity.builder()
                .name(name).displayName(name).priority(RolePriority.priority(name)).isSystem(false)
                .build();
    }
}
