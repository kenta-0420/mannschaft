package com.mannschaft.app.committee.event;

import com.mannschaft.app.auth.service.UserRowLockService;
import com.mannschaft.app.committee.entity.CommitteeEntity;
import com.mannschaft.app.committee.entity.CommitteeInvitationEntity;
import com.mannschaft.app.committee.entity.CommitteeInvitationResolution;
import com.mannschaft.app.committee.entity.CommitteeMemberEntity;
import com.mannschaft.app.committee.entity.CommitteeRole;
import com.mannschaft.app.committee.entity.CommitteeStatus;
import com.mannschaft.app.committee.error.CommitteeErrorCode;
import com.mannschaft.app.committee.repository.CommitteeInvitationRepository;
import com.mannschaft.app.committee.repository.CommitteeMemberRepository;
import com.mannschaft.app.committee.repository.CommitteeRepository;
import com.mannschaft.app.committee.service.CommitteeInvitationService;
import com.mannschaft.app.committee.service.CommitteeService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.role.event.MembershipChangedEvent;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 組織脱退イベントと委員会メンバーシップの実 MySQL トランザクション・競合契約。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("組織脱退時の委員会クリーンアップ 統合テスト")
class CommitteeOrganizationMembershipCleanupIntegrationTest extends AbstractMySqlIntegrationTest {

    private static final long TIMEOUT_SECONDS = 10L;

    @Autowired private OrganizationMembershipCommitteeCleanupListener listener;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private CommitteeRepository committeeRepository;
    @Autowired private CommitteeMemberRepository committeeMemberRepository;
    @Autowired private CommitteeInvitationRepository committeeInvitationRepository;
    @Autowired private CommitteeService committeeService;
    @Autowired private CommitteeInvitationService committeeInvitationService;
    @Autowired private UserRowLockService userRowLockService;
    @Autowired private TransactionTemplate transactionTemplate;
    @PersistenceContext private EntityManager entityManager;

    private final Set<Long> committeeIds = new LinkedHashSet<>();
    private final Set<Long> userIds = new LinkedHashSet<>();
    private final Set<Long> organizationIds = new LinkedHashSet<>();

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(tx -> {
            for (Long committeeId : committeeIds) {
                entityManager.createNativeQuery("DELETE FROM committee_invitations WHERE committee_id = :id")
                        .setParameter("id", committeeId).executeUpdate();
                entityManager.createNativeQuery("DELETE FROM committee_members WHERE committee_id = :id")
                        .setParameter("id", committeeId).executeUpdate();
                entityManager.createNativeQuery("DELETE FROM committees WHERE id = :id")
                        .setParameter("id", committeeId).executeUpdate();
            }
            for (Long userId : userIds) {
                entityManager.createNativeQuery("DELETE FROM memberships WHERE user_id = :id")
                        .setParameter("id", userId).executeUpdate();
                entityManager.createNativeQuery("DELETE FROM users WHERE id = :id")
                        .setParameter("id", userId).executeUpdate();
            }
            for (Long organizationId : organizationIds) {
                entityManager.createNativeQuery("DELETE FROM organizations WHERE id = :id")
                        .setParameter("id", organizationId).executeUpdate();
            }
        });
    }

    @Test
    @DisplayName("同期リスナーをトランザクション外から直接呼ぶとMANDATORYで拒否する")
    void トランザクション外呼出し_MANDATORYで拒否する() {
        assertThatThrownBy(() -> listener.onMembershipChanged(removedEvent(1L, 2L)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("Springイベントで委員長を交代し未解決招集をキャンセルする")
    void Springイベント_後継選定と招集取消を同一Txで実行する() {
        Fixture fixture = createFixture(710_001L, false, true);

        transactionTemplate.executeWithoutResult(tx -> eventPublisher.publishEvent(
                removedEvent(fixture.removedUserId(), fixture.organizationId())));

        assertThat(committeeMemberRepository.findById(fixture.removedMemberId()).orElseThrow().getLeftAt())
                .isNotNull();
        assertThat(committeeMemberRepository.findById(fixture.successorMemberId()).orElseThrow().getRole())
                .isEqualTo(CommitteeRole.CHAIR);
        CommitteeInvitationEntity invitation = committeeInvitationRepository
                .findById(fixture.invitationId()).orElseThrow();
        assertThat(invitation.getResolution()).isEqualTo(CommitteeInvitationResolution.CANCELLED);
    }

    @Test
    @DisplayName("イベント後に発行元が失敗すると委員会終了・昇格・招集取消もロールバックする")
    void 発行元失敗_クリーンアップ全体をロールバックする() {
        Fixture fixture = createFixture(710_002L, false, true);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
            eventPublisher.publishEvent(removedEvent(fixture.removedUserId(), fixture.organizationId()));
            throw new RollbackMarkerException();
        })).isInstanceOf(RollbackMarkerException.class);

        assertThat(committeeMemberRepository.findById(fixture.removedMemberId()).orElseThrow().getLeftAt())
                .isNull();
        assertThat(committeeMemberRepository.findById(fixture.successorMemberId()).orElseThrow().getRole())
                .isEqualTo(CommitteeRole.VICE_CHAIR);
        assertThat(committeeInvitationRepository.findById(fixture.invitationId()).orElseThrow().getResolvedAt())
                .isNull();
    }

    @Test
    @DisplayName("論理削除済み委員会もnativeロッククエリで取得して現役メンバーを終了する")
    void 論理削除済み委員会_現役メンバーを終了する() {
        Fixture fixture = createFixture(710_003L, true, true);

        transactionTemplate.executeWithoutResult(tx -> eventPublisher.publishEvent(
                removedEvent(fixture.removedUserId(), fixture.organizationId())));

        assertThat(committeeMemberRepository.findById(fixture.removedMemberId()).orElseThrow().getLeftAt())
                .isNotNull();
        assertThat(committeeInvitationRepository.findById(fixture.invitationId()).orElseThrow().getResolution())
                .isEqualTo(CommitteeInvitationResolution.CANCELLED);
    }

    @Test
    @DisplayName("組織脱退cleanup中の通常役職変更はcommitを待ち最新認可で拒否される")
    void cleanupと通常役職変更_親行ロックで直列化する() throws Exception {
        Fixture fixture = createFixture(710_004L, false, false);
        CountDownLatch cleanupApplied = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        CountDownLatch mutationStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> cleanup = executor.submit(() -> transactionTemplate.executeWithoutResult(tx -> {
                eventPublisher.publishEvent(removedEvent(fixture.removedUserId(), fixture.organizationId()));
                cleanupApplied.countDown();
                await(releaseCleanup);
            }));
            assertThat(cleanupApplied.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> mutation = executor.submit(() -> {
                mutationStarted.countDown();
                try {
                    committeeService.updateMemberRole(
                            fixture.committeeId(), fixture.successorUserId(),
                            CommitteeRole.MEMBER, fixture.removedUserId());
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
            assertThat(mutationStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(waitUntilDone(mutation, 300)).as("committee親行のlock待ちで未完了").isFalse();

            releaseCleanup.countDown();
            cleanup.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Throwable failure = mutation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(failure).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) failure).getErrorCode()).isEqualTo(CommonErrorCode.COMMON_002);
            assertThat(committeeMemberRepository.findById(fixture.successorMemberId()).orElseThrow().getRole())
                    .isEqualTo(CommitteeRole.CHAIR);
        } finally {
            releaseCleanup.countDown();
            shutdown(executor);
        }
    }

    @Test
    @DisplayName("組織脱退と招集受諾が競合すると受諾はuserロック後の所属再検査で拒否される")
    void cleanupと招集受諾_userロックで直列化する() throws Exception {
        AcceptanceFixture fixture = createAcceptanceFixture();
        CountDownLatch cleanupApplied = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        CountDownLatch acceptanceStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> cleanup = executor.submit(() -> transactionTemplate.executeWithoutResult(tx -> {
                userRowLockService.lockAll(fixture.userId());
                entityManager.createNativeQuery("""
                                UPDATE memberships
                                   SET left_at = NOW(), leave_reason = 'REMOVED'
                                 WHERE user_id = :userId
                                   AND scope_type = 'ORGANIZATION'
                                   AND scope_id = :organizationId
                                   AND left_at IS NULL
                                """)
                        .setParameter("userId", fixture.userId())
                        .setParameter("organizationId", fixture.organizationId())
                        .executeUpdate();
                eventPublisher.publishEvent(removedEvent(fixture.userId(), fixture.organizationId()));
                cleanupApplied.countDown();
                await(releaseCleanup);
            }));
            assertThat(cleanupApplied.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> acceptance = executor.submit(() -> {
                acceptanceStarted.countDown();
                try {
                    committeeInvitationService.acceptInvitation(fixture.token(), fixture.userId());
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
            assertThat(acceptanceStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(waitUntilDone(acceptance, 300)).as("user行のlock待ちで未完了").isFalse();

            releaseCleanup.countDown();
            cleanup.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Throwable failure = acceptance.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(failure).isInstanceOf(BusinessException.class);
            assertThat(committeeMemberRepository.existsByCommitteeIdAndUserIdAndLeftAtIsNull(
                    fixture.committeeId(), fixture.userId())).isFalse();
            assertThat(committeeInvitationRepository.findById(fixture.invitationId()).orElseThrow().getResolution())
                    .isEqualTo(CommitteeInvitationResolution.CANCELLED);
        } finally {
            releaseCleanup.countDown();
            shutdown(executor);
        }
    }

    @Test
    @DisplayName("CLOSED committee invitation cannot create a new active member")
    void closedCommitteeInvitationIsRejected() {
        AcceptanceFixture fixture = createAcceptanceFixture();
        transactionTemplate.executeWithoutResult(tx -> {
            CommitteeEntity committee = committeeRepository.findById(fixture.committeeId()).orElseThrow();
            committee.applyStatusTransition(CommitteeStatus.CLOSED, null);
            committeeRepository.save(committee);
        });

        assertThatThrownBy(() -> committeeInvitationService.acceptInvitation(
                fixture.token(), fixture.userId()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommitteeErrorCode.INVITATION_EXPIRED));
        assertThat(committeeMemberRepository.existsByCommitteeIdAndUserIdAndLeftAtIsNull(
                fixture.committeeId(), fixture.userId())).isFalse();
    }

    private Fixture createFixture(long organizationId, boolean deleted, boolean withInvitation) {
        return transactionTemplate.execute(tx -> {
            long removedUserId = organizationId + 100;
            long successorUserId = organizationId + 200;
            CommitteeEntity committee = committeeRepository.save(CommitteeEntity.builder()
                    .organizationId(organizationId)
                    .name("cleanup-it-" + UUID.randomUUID())
                    .status(CommitteeStatus.ACTIVE)
                    .build());
            committeeIds.add(committee.getId());
            CommitteeMemberEntity removed = committeeMemberRepository.save(CommitteeMemberEntity.builder()
                    .committeeId(committee.getId()).userId(removedUserId)
                    .role(CommitteeRole.CHAIR).joinedAt(LocalDateTime.now().minusDays(2)).build());
            CommitteeMemberEntity successor = committeeMemberRepository.save(CommitteeMemberEntity.builder()
                    .committeeId(committee.getId()).userId(successorUserId)
                    .role(CommitteeRole.VICE_CHAIR).joinedAt(LocalDateTime.now().minusDays(1)).build());
            CommitteeInvitationEntity invitation = null;
            if (withInvitation) {
                invitation = committeeInvitationRepository.save(CommitteeInvitationEntity.builder()
                        .committeeId(committee.getId()).inviteeUserId(removedUserId)
                        .inviteToken(UUID.randomUUID().toString()).expiresAt(LocalDateTime.now().plusDays(1))
                        .build());
            }
            if (deleted) {
                entityManager.flush();
                entityManager.createNativeQuery("UPDATE committees SET deleted_at = NOW() WHERE id = :id")
                        .setParameter("id", committee.getId()).executeUpdate();
                entityManager.clear();
            }
            return new Fixture(organizationId, committee.getId(), removedUserId, successorUserId,
                    removed.getId(), successor.getId(), invitation == null ? null : invitation.getId());
        });
    }

    private AcceptanceFixture createAcceptanceFixture() {
        return transactionTemplate.execute(tx -> {
            Long organizationId = insertOrganization();
            Long userId = insertUser();
            MembershipTestHelper.insertMembership(
                    entityManager, userId, ScopeType.ORGANIZATION, organizationId, RoleKind.MEMBER);
            CommitteeEntity committee = committeeRepository.save(CommitteeEntity.builder()
                    .organizationId(organizationId).name("accept-lock-" + UUID.randomUUID())
                    .status(CommitteeStatus.ACTIVE).build());
            committeeIds.add(committee.getId());
            String token = UUID.randomUUID().toString();
            CommitteeInvitationEntity invitation = committeeInvitationRepository.save(
                    CommitteeInvitationEntity.builder()
                            .committeeId(committee.getId()).inviteeUserId(userId)
                            .inviteToken(token).expiresAt(LocalDateTime.now().plusDays(1)).build());
            return new AcceptanceFixture(
                    organizationId, userId, committee.getId(), invitation.getId(), token);
        });
    }

    private Long insertUser() {
        String email = "committee-cleanup-" + UUID.randomUUID() + "@example.com";
        entityManager.createNativeQuery("""
                        INSERT INTO users
                            (email, last_name, first_name, display_name, status,
                             is_searchable, handle_searchable, contact_approval_required,
                             online_visibility, dm_receive_from, encryption_key_version,
                             locale, timezone, reporting_restricted, follow_list_visibility,
                             care_notification_enabled, offline_only, created_at, updated_at)
                        VALUES
                            (:email, '委員会', '競合', '委員会競合', 'ACTIVE',
                             1, 1, 1, 'NOBODY', 'ANYONE', 1,
                             'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())
                        """)
                .setParameter("email", email)
                .executeUpdate();
        Long id = ((Number) entityManager.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email).getSingleResult()).longValue();
        userIds.add(id);
        return id;
    }

    private Long insertOrganization() {
        String name = "committee-cleanup-org-" + UUID.randomUUID();
        String slug = "cco-" + UUID.randomUUID().toString().substring(0, 8);
        entityManager.createNativeQuery("""
                        INSERT INTO organizations
                            (name, org_type, visibility, hierarchy_visibility, supporter_enabled,
                             version, slug, created_at, updated_at)
                        VALUES
                            (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, :slug, NOW(), NOW())
                        """)
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        Long id = ((Number) entityManager.createNativeQuery("SELECT id FROM organizations WHERE slug = :slug")
                .setParameter("slug", slug).getSingleResult()).longValue();
        organizationIds.add(id);
        return id;
    }

    private static MembershipChangedEvent removedEvent(Long userId, Long organizationId) {
        return new MembershipChangedEvent(
                userId, "ORGANIZATION", organizationId, MembershipChangedEvent.ChangeType.REMOVED);
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
                throw new IllegalStateException("latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    }

    private record Fixture(
            Long organizationId,
            Long committeeId,
            Long removedUserId,
            Long successorUserId,
            Long removedMemberId,
            Long successorMemberId,
            Long invitationId) {
    }

    private record AcceptanceFixture(
            Long organizationId,
            Long userId,
            Long committeeId,
            Long invitationId,
            String token) {
    }

    private static final class RollbackMarkerException extends RuntimeException {
    }
}
