package com.mannschaft.app.role;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.InviteTokenRepository;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** 個人招待世代の実DB試練用。通常fixtureはEntity/各domain Repositoryで明示commitする。 */
abstract class InviteGenerationItSupport extends AbstractMySqlIntegrationTest {
    @Autowired protected InviteTokenRepository tokens;
    @Autowired protected RoleRepository roles;
    @Autowired protected UserRoleRepository userRoles;
    @Autowired protected TeamRepository teams;
    @Autowired protected OrganizationRepository organizations;
    @Autowired protected UserRepository users;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactionManager;

    private final List<Scope> ownedScopes = new ArrayList<>();
    private final List<Long> ownedUsers = new ArrayList<>();
    private final List<Long> ownedRoles = new ArrayList<>();

    protected record Scope(String type, String table, Long id, Long memberRoleId) { }

    protected <T> T inTx(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    protected Scope scope(String type) {
        return inTx(() -> {
            String slug = "igs-" + UUID.randomUUID().toString().substring(0, 18);
            Long id = "TEAM".equals(type)
                    ? teams.saveAndFlush(TeamEntity.builder().name("世代試練チーム").slug(slug)
                            .visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(true).build()).getId()
                    : organizations.saveAndFlush(OrganizationEntity.builder().name("世代試練組織").slug(slug)
                            .orgType(OrganizationEntity.OrgType.OTHER).visibility(OrganizationEntity.Visibility.PUBLIC)
                            .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                            .supporterEnabled(true).build()).getId();
            RoleEntity role = roles.findByName("MEMBER").orElseGet(() -> {
                RoleEntity created = roles.saveAndFlush(RoleEntity.builder().name("MEMBER").displayName("メンバー")
                        .priority(4).isSystem(false).build());
                ownedRoles.add(created.getId());
                return created;
            });
            Scope scope = new Scope(type, "TEAM".equals(type) ? "teams" : "organizations", id, role.getId());
            ownedScopes.add(scope);
            return scope;
        });
    }

    protected Long user() {
        return inTx(() -> {
            Long id = users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID() + "@example.com")
                    .lastName("世代").firstName("試練").displayName("世代試練")
                    .isSearchable(true).locale("ja").timezone("Asia/Tokyo")
                    .status(UserEntity.UserStatus.ACTIVE).build()).getId();
            ownedUsers.add(id);
            return id;
        });
    }

    protected Long admin(Scope scope) {
        return actorWithRole(scope, "ADMIN", 2);
    }

    protected Long actorWithRole(Scope scope, String name, int priority) {
        Long userId = user();
        return inTx(() -> {
            RoleEntity adminRole = roles.findByName(name).orElseGet(() -> {
                RoleEntity created = roles.saveAndFlush(RoleEntity.builder().name(name).displayName(name)
                        .priority(priority).isSystem(false).build());
                ownedRoles.add(created.getId());
                return created;
            });
            var builder = UserRoleEntity.builder().userId(userId).roleId(adminRole.getId()).grantedBy(userId);
            if ("TEAM".equals(scope.type())) {
                builder.teamId(scope.id());
            } else {
                builder.organizationId(scope.id());
            }
            userRoles.saveAndFlush(builder.build());
            return userId;
        });
    }

    protected InviteTokenEntity token(Scope scope, long generation, LocalDateTime expires,
                                      Integer maxUses, int usedCount, Long target, LocalDateTime revoked) {
        return inTx(() -> {
            var builder = InviteTokenEntity.builder().token(UUID.randomUUID().toString())
                    .roleId(scope.memberRoleId()).scopeClosureGeneration(generation)
                    .expiresAt(expires).maxUses(maxUses).usedCount(usedCount).targetUserId(target).revokedAt(revoked);
            if ("TEAM".equals(scope.type())) {
                builder.teamId(scope.id());
            } else {
                builder.organizationId(scope.id());
            }
            return tokens.saveAndFlush(builder.build());
        });
    }

    protected InviteTokenEntity token(Scope scope, long generation) {
        return token(scope, generation, null, null, 0, null, null);
    }

    @AfterEach
    void removeOwnedFixtures() {
        for (Scope scope : ownedScopes) {
            String scopeColumn = "TEAM".equals(scope.type()) ? "team_id" : "organization_id";
            jdbc.update("DELETE FROM invite_tokens WHERE " + scopeColumn + " = ?", scope.id());
        }
        for (Long userId : ownedUsers) {
            jdbc.update("DELETE FROM user_permission_groups WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM user_roles WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM memberships WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM my_scope_folder_items WHERE folder_id IN (SELECT id FROM my_scope_folders WHERE user_id = ?)", userId);
            jdbc.update("DELETE FROM my_scope_folders WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
        for (Scope scope : ownedScopes) {
            jdbc.update("DELETE FROM " + scope.table() + " WHERE id = ?", scope.id());
        }
        for (Long roleId : ownedRoles) {
            jdbc.update("DELETE FROM roles WHERE id = ?", roleId);
        }
    }
}
