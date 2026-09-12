package com.mannschaft.app.common.storage.acl;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.StorageErrorCode;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 実 MySQL で条件付き claim SQL の競合遮断を検証する。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class StorageAclRepositoryIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private StorageAclRepository repository;

    @Autowired
    private StorageAclService service;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void pending登録はFlyway必須監査列も埋める() {
        StorageAclEntity saved = repository.saveAndFlush(pending(
                "integration/storage-acl-audit-" + System.nanoTime()));

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void 並行claimは一方だけが成功しもう一方は409になる() throws Exception {
        String fileKey = "integration/storage-acl-claim-" + System.nanoTime();
        repository.saveAndFlush(pending(fileKey));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        CompletableFuture<Boolean> first = CompletableFuture.supplyAsync(() -> claimAfterStart(ready, start, fileKey, "101"));
        CompletableFuture<Boolean> second = CompletableFuture.supplyAsync(() -> claimAfterStart(ready, start, fileKey, "102"));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        assertThat((first.get(10, TimeUnit.SECONDS) ? 1 : 0) + (second.get(10, TimeUnit.SECONDS) ? 1 : 0)).isEqualTo(1);
        assertThat(repository.findByFileKey(fileKey)).get().extracting(StorageAclEntity::getStatus)
                .isEqualTo(StorageAclStatus.CLAIMED);
    }

    @Test
    void 期限ちょうどNはexpiresAtより大きい条件に含まれずclaimできない() {
        String fileKey = "integration/storage-acl-expiry-" + System.nanoTime();
        StorageAclEntity acl = pending(fileKey).toBuilder()
                .expiresAt(Instant.now(Clock.systemUTC()).minusSeconds(1)).build();
        repository.saveAndFlush(acl);

        Integer affected = requiresNewTransaction().execute(status -> repository.claimPending(
                fileKey, 9001L, StorageAclScopeType.TEAM.name(), "9002",
                "ATTACHMENT", "101"));

        assertThat(affected).isZero();
    }

    @Test
    void claim後にロールバックするとPENDINGのまま残る() {
        String fileKey = "integration/storage-acl-rollback-" + System.nanoTime();
        TransactionTemplate tx = requiresNewTransaction();
        tx.executeWithoutResult(status -> repository.saveAndFlush(pending(fileKey)));

        tx.executeWithoutResult(status -> {
            service.claimPending(fileKey, 9001L, StorageAclScope.team(9002L),
                    new StorageAclAttachmentBinding("ATTACHMENT", "101"));
            status.setRollbackOnly();
        });

        StorageAclStatus actual = tx.execute(status -> repository.findByFileKey(fileKey)
                .orElseThrow().getStatus());
        assertThat(actual).isEqualTo(StorageAclStatus.PENDING);
    }

    private boolean claimAfterStart(CountDownLatch ready, CountDownLatch start, String fileKey, String key) {
        try {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("競合試験の開始待機がタイムアウトしました");
            }
            service.claimPending(fileKey, 9001L, StorageAclScope.team(9002L),
                    new StorageAclAttachmentBinding("ATTACHMENT", key));
            return true;
        } catch (BusinessException expected) {
            if (expected.getErrorCode() == StorageErrorCode.ACL_CLAIM_CONFLICT) {
                return false;
            }
            throw new AssertionError(expected);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private StorageAclEntity pending(String fileKey) {
        return StorageAclEntity.builder().fileKey(fileKey).ownerId(9001L).scopeType(StorageAclScopeType.TEAM)
                .scopeKey("9002").aclMode(StorageAclMode.CONTENT_BOUND).contentType("image/png")
                .parentContentReferenceType("WORKFLOW_REQUEST").parentContentReferenceKey("42")
                .status(StorageAclStatus.PENDING).expiresAt(Instant.now(Clock.systemUTC()).plusSeconds(900))
                .build();
    }

    private TransactionTemplate requiresNewTransaction() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }
}
