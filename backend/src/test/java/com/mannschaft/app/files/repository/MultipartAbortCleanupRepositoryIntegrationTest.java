package com.mannschaft.app.files.repository;

import com.mannschaft.app.files.entity.MultipartAbortCleanupEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Flyway適用済み実MySQLでcleanup台帳の期限更新・claim排他・保持期限を検証する。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class MultipartAbortCleanupRepositoryIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private MultipartAbortCleanupRepository repository;
    @Autowired
    private EntityManager entityManager;

    @Test
    void lease期限前等号後を実更新する() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        MultipartAbortCleanupEntity before = save("lease-before", "CLAIMED", now.minusSeconds(1), null);
        MultipartAbortCleanupEntity equal = save("lease-equal", "CLAIMED", now, null);
        MultipartAbortCleanupEntity after = save("lease-after", "CLAIMED", now.plusSeconds(1), null);

        repository.releaseExpiredClaims(now);
        entityManager.clear();

        assertThat(repository.findById(before.getId()).orElseThrow().getStatus()).isEqualTo("ABORT_PENDING");
        assertThat(repository.findById(equal.getId()).orElseThrow().getStatus()).isEqualTo("ABORT_PENDING");
        assertThat(repository.findById(after.getId()).orElseThrow().getStatus()).isEqualTo("CLAIMED");
    }

    @Test
    void 同一行の同時claimは一件だけ成功する() throws Exception {
        MultipartAbortCleanupEntity item = save("claim-race", "ABORT_PENDING", null, null);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { start.await(); return repository.claim(item.getId(), now, now.plusMinutes(10)); });
            var second = pool.submit(() -> { start.await(); return repository.claim(item.getId(), now, now.plusMinutes(10)); });
            start.countDown();
            int claimed = first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS);
            assertThat(claimed).isEqualTo(1);
            entityManager.clear();
            assertThat(repository.findById(item.getId()).orElseThrow().getStatus()).isEqualTo("CLAIMED");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void deadLetter保持期限の前等号後を実クエリで抽出する() {
        LocalDateTime cutoff = LocalDateTime.now().withNano(0);
        MultipartAbortCleanupEntity old = save("dead-old", "DEAD_LETTER", null, cutoff.minusSeconds(1));
        MultipartAbortCleanupEntity equal = save("dead-equal", "DEAD_LETTER", null, cutoff);
        MultipartAbortCleanupEntity recent = save("dead-recent", "DEAD_LETTER", null, cutoff.plusSeconds(1));

        List<MultipartAbortCleanupEntity> expired = repository
                .findByStatusAndDeadLetteredAtBefore("DEAD_LETTER", cutoff);

        assertThat(expired).extracting(MultipartAbortCleanupEntity::getId)
                .contains(old.getId()).doesNotContain(equal.getId(), recent.getId());
    }

    private MultipartAbortCleanupEntity save(String suffix, String status, LocalDateTime lease,
                                             LocalDateTime deadLetteredAt) {
        return repository.saveAndFlush(MultipartAbortCleanupEntity.builder()
                .uploadId("it-" + suffix + "-" + System.nanoTime()).r2Key("it/key/" + suffix)
                .ownerId(1L).contentType("video/mp4").feature("files")
                .scopeType("PERSONAL").scopeId(1L).status(status)
                .nextAttemptAt(LocalDateTime.now()).attemptCount(0).leaseUntil(lease)
                .deadLetteredAt(deadLetteredAt).build());
    }
}
