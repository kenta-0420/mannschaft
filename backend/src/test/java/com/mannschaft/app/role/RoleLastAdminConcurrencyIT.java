package com.mannschaft.app.role;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.service.RoleService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** TEAMの異なるADMINを同時に離脱させても最後のADMINを失わないことを実DBで検証する。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RoleLastAdminConcurrencyIT extends AbstractMySqlIntegrationTest {

    private static final long TEAM_ID = 991401L;
    private static final long TIMEOUT_SECONDS = 10L;

    @Autowired private RoleService roleService;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleRepository userRoleRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @PersistenceContext private EntityManager entityManager;

    private Long adminRoleId;
    private boolean createdAdminRole;
    private Long firstUserId;
    private Long secondUserId;

    @BeforeEach
    void setUp() {
        adminRoleId = roleRepository.findByName("ADMIN").map(RoleEntity::getId).orElseGet(() -> {
            createdAdminRole = true;
            return roleRepository.save(RoleEntity.builder()
                    .name("ADMIN").displayName("ADMIN").priority(2).isSystem(false).build()).getId();
        });
        firstUserId = saveUser("first");
        secondUserId = saveUser("second");
        enroll(firstUserId);
        enroll(secondUserId);
    }

    @AfterEach
    void tearDown() {
        if (firstUserId != null && secondUserId != null) {
            transactionTemplate.executeWithoutResult(tx -> {
                entityManager.createNativeQuery("DELETE FROM memberships "
                                + "WHERE user_id IN (:firstUserId, :secondUserId) "
                                + "AND scope_type = 'TEAM' AND scope_id = :teamId")
                        .setParameter("firstUserId", firstUserId)
                        .setParameter("secondUserId", secondUserId)
                        .setParameter("teamId", TEAM_ID)
                        .executeUpdate();
                entityManager.createNativeQuery("DELETE FROM user_roles "
                                + "WHERE user_id IN (:firstUserId, :secondUserId) AND team_id = :teamId")
                        .setParameter("firstUserId", firstUserId)
                        .setParameter("secondUserId", secondUserId)
                        .setParameter("teamId", TEAM_ID)
                        .executeUpdate();
                entityManager.createNativeQuery("DELETE FROM users WHERE id IN (:firstUserId, :secondUserId)")
                        .setParameter("firstUserId", firstUserId)
                        .setParameter("secondUserId", secondUserId)
                        .executeUpdate();
            });
        }
        if (createdAdminRole && adminRoleId != null) roleRepository.deleteById(adminRoleId);
    }

    @Test
    void twoAdminsLeaveConcurrently_oneIsProtected() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> first = submit(firstUserId, ready, start, executor);
            Future<Throwable> second = submit(secondUserId, ready, start, executor);
            assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            Throwable firstError = first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Throwable secondError = second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            List<Throwable> errors = Arrays.asList(firstError, secondError);
            assertThat(errors).filteredOn(Objects::nonNull).hasSize(1)
                    .first().isInstanceOfSatisfying(BusinessException.class, error ->
                            assertThat(error.getErrorCode().getCode()).isEqualTo("ROLE_004"));
            assertThat(errors).contains((Throwable) null);
            assertThat(userRoleRepository.countByTeamIdAndRoleId(TEAM_ID, adminRoleId)).isGreaterThanOrEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Future<Throwable> submit(Long userId, CountDownLatch ready, CountDownLatch start,
                                     ExecutorService executor) {
        return executor.submit(() -> {
            ready.countDown();
            if (!start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("concurrency test start timed out");
            }
            try {
                roleService.leaveScope(userId, TEAM_ID, "TEAM");
                return null;
            } catch (Throwable error) {
                return error;
            }
        });
    }

    private Long saveUser(String suffix) {
        UserEntity user = UserEntity.builder()
                .email("role-last-admin-" + suffix + "-" + System.nanoTime() + "@example.com")
                .lastName("ADMIN").firstName(suffix).displayName("ADMIN " + suffix)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo")
                .isSearchable(true).build();
        return userRepository.save(user).getId();
    }

    private void enroll(Long userId) {
        userRoleRepository.save(UserRoleEntity.builder().userId(userId).roleId(adminRoleId)
                .teamId(TEAM_ID).build());
        membershipRepository.save(MembershipEntity.builder().userId(userId).scopeType(ScopeType.TEAM)
                .scopeId(TEAM_ID).roleKind(RoleKind.MEMBER).joinedAt(LocalDateTime.now()).build());
    }
}
