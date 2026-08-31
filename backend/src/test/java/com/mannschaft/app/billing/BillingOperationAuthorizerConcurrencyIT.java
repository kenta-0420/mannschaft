package com.mannschaft.app.billing;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.role.dto.PermissionGroupRequest;
import com.mannschaft.app.role.dto.UserPermissionGroupAssignRequest;
import com.mannschaft.app.role.entity.PermissionEntity;
import com.mannschaft.app.role.entity.PermissionGroupEntity;
import com.mannschaft.app.role.entity.PermissionGroupPermissionEntity;
import com.mannschaft.app.role.entity.UserPermissionGroupEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.service.PermissionGroupService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 課金操作の認可確定と permission group 解除を実 MySQL の行ロックで直列化できることを検証する。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("BillingOperationAuthorizer 実 MySQL 直列化契約")
class BillingOperationAuthorizerConcurrencyIT extends AbstractMySqlIntegrationTest {

    private static final long TEAM_ID = 894_311L;
    private static final long TIMEOUT_SECONDS = 10L;
    private static final String BILLING_PERMISSION = "MANAGE_TEAM_BILLING";

    @Autowired private BillingOperationAuthorizer authorizer;
    @Autowired private PermissionGroupService permissionGroupService;
    @Autowired private TransactionTemplate transactionTemplate;
    @PersistenceContext private EntityManager entityManager;

    private Long adminId;
    private Long deputyId;
    private Long groupId;
    private Long harmlessPermissionId;

    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(tx -> {
            adminId = insertUser("admin");
            deputyId = insertUser("deputy");
            grantTeamRole(adminId, "ADMIN");
            grantTeamRole(deputyId, "DEPUTY_ADMIN");
            enroll(adminId);
            enroll(deputyId);

            Long billingPermissionId = permissionId(BILLING_PERMISSION);
            harmlessPermissionId = insertHarmlessPermission();
            PermissionGroupEntity group = PermissionGroupEntity.builder()
                    .teamId(TEAM_ID)
                    .targetRole(PermissionGroupEntity.TargetRole.DEPUTY_ADMIN)
                    .name("billing-operation-lock-" + System.nanoTime())
                    .createdBy(adminId)
                    .build();
            entityManager.persist(group);
            entityManager.flush();
            groupId = group.getId();
            entityManager.persist(PermissionGroupPermissionEntity.builder()
                    .groupId(groupId).permissionId(billingPermissionId).build());
            entityManager.persist(UserPermissionGroupEntity.builder()
                    .userId(deputyId).groupId(groupId).assignedBy(adminId).build());
        });
    }

    @AfterEach
    void tearDown() {
        if (adminId == null || deputyId == null) {
            return;
        }
        transactionTemplate.executeWithoutResult(tx -> {
            entityManager.createNativeQuery("DELETE FROM user_permission_groups WHERE user_id = :userId")
                    .setParameter("userId", deputyId).executeUpdate();
            if (groupId != null) {
                entityManager.createNativeQuery("DELETE FROM permission_group_permissions WHERE group_id = :groupId")
                        .setParameter("groupId", groupId).executeUpdate();
                entityManager.createNativeQuery("DELETE FROM permission_groups WHERE id = :groupId")
                        .setParameter("groupId", groupId).executeUpdate();
            }
            entityManager.createNativeQuery("DELETE FROM memberships WHERE user_id IN (:adminId, :deputyId)")
                    .setParameter("adminId", adminId).setParameter("deputyId", deputyId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM user_roles WHERE user_id IN (:adminId, :deputyId)")
                    .setParameter("adminId", adminId).setParameter("deputyId", deputyId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM users WHERE id IN (:adminId, :deputyId)")
                    .setParameter("adminId", adminId).setParameter("deputyId", deputyId).executeUpdate();
            if (harmlessPermissionId != null) {
                entityManager.createNativeQuery("DELETE FROM permissions WHERE id = :permissionId")
                        .setParameter("permissionId", harmlessPermissionId).executeUpdate();
            }
        });
    }

    @Test
    @DisplayName("操作が先にpermission group行をロックすると権限除去updateは操作commitまで待つ")
    void operationFirst_groupPermissionRemovalWaitsForCommit() throws Exception {
        CountDownLatch operationAuthorized = new CountDownLatch(1);
        CountDownLatch releaseOperation = new CountDownLatch(1);
        CountDownLatch revocationStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> operation = submitAuthorizedOperation(executor, operationAuthorized, releaseOperation);
            assertThat(operationAuthorized.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            Future<?> revocation = executor.submit(() -> {
                revocationStarted.countDown();
                permissionGroupService.updatePermissionGroup(groupId,
                        new PermissionGroupRequest("billing-revoked", "DEPUTY_ADMIN",
                                List.of(harmlessPermissionId)), adminId);
            });
            assertThat(revocationStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(revocation.isDone()).as("permission group行のlock待ちで未完了").isFalse();
            assertThat(waitUntilDone(revocation, 300)).isFalse();

            releaseOperation.countDown();
            operation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            revocation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertSubsequentOperationDenied();
        } finally {
            releaseOperation.countDown();
            shutdown(executor);
        }
    }

    @Test
    @DisplayName("操作が先に操作者user行をロックすると割当解除は操作commitまで待つ")
    void operationFirst_assignmentClearWaitsForCommit() throws Exception {
        CountDownLatch operationAuthorized = new CountDownLatch(1);
        CountDownLatch releaseOperation = new CountDownLatch(1);
        CountDownLatch revocationStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> operation = submitAuthorizedOperation(executor, operationAuthorized, releaseOperation);
            assertThat(operationAuthorized.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            Future<?> revocation = executor.submit(() -> {
                revocationStarted.countDown();
                permissionGroupService.assignUserPermissionGroups(
                        deputyId, TEAM_ID, "TEAM", new UserPermissionGroupAssignRequest(List.of()), adminId);
            });
            assertThat(revocationStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(revocation.isDone()).as("操作者user行のlock待ちで未完了").isFalse();
            assertThat(waitUntilDone(revocation, 300)).isFalse();

            releaseOperation.countDown();
            operation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            revocation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertSubsequentOperationDenied();
        } finally {
            releaseOperation.countDown();
            shutdown(executor);
        }
    }

    private Future<?> submitAuthorizedOperation(
            ExecutorService executor,
            CountDownLatch operationAuthorized,
            CountDownLatch releaseOperation) {
        return executor.submit(() -> transactionTemplate.executeWithoutResult(tx -> {
            authorizer.requireCanManage(deputyId, EntitlementScopeKind.TEAM, TEAM_ID);
            operationAuthorized.countDown();
            await(releaseOperation);
        }));
    }

    private void assertSubsequentOperationDenied() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx ->
                authorizer.requireCanManage(deputyId, EntitlementScopeKind.TEAM, TEAM_ID)))
                .isInstanceOf(BusinessException.class);
    }

    private Long insertUser(String suffix) {
        UserEntity user = UserEntity.builder()
                .email("billing-operation-lock-" + suffix + "-" + System.nanoTime() + "@example.com")
                .lastName("課金").firstName(suffix).displayName("課金 " + suffix)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo")
                .isSearchable(true).build();
        entityManager.persist(user);
        entityManager.flush();
        return user.getId();
    }

    private void grantTeamRole(Long userId, String roleName) {
        Number roleId = (Number) entityManager.createNativeQuery("SELECT id FROM roles WHERE name = :name")
                .setParameter("name", roleName).getSingleResult();
        entityManager.persist(UserRoleEntity.builder().userId(userId).roleId(roleId.longValue())
                .teamId(TEAM_ID).build());
    }

    private void enroll(Long userId) {
        entityManager.persist(MembershipEntity.builder()
                .userId(userId).scopeType(ScopeType.TEAM).scopeId(TEAM_ID)
                .roleKind(RoleKind.MEMBER).joinedAt(LocalDateTime.now()).build());
    }

    private Long permissionId(String name) {
        Number id = (Number) entityManager.createNativeQuery("SELECT id FROM permissions WHERE name = :name")
                .setParameter("name", name).getSingleResult();
        return id.longValue();
    }

    private Long insertHarmlessPermission() {
        PermissionEntity permission = PermissionEntity.builder()
                .name("BILLING_LOCK_HARMLESS_" + System.nanoTime())
                .displayName("直列化テスト用非課金権限")
                .scope(PermissionEntity.Scope.TEAM)
                .build();
        entityManager.persist(permission);
        entityManager.flush();
        return permission.getId();
    }

    private static boolean waitUntilDone(Future<?> future, long millis) throws Exception {
        try {
            future.get(millis, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException expected) {
            return false;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("課金認可直列化テストのlatch待機がtimeoutしました");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("課金認可直列化テストがinterruptされました", interrupted);
        }
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    }
}
