package com.mannschaft.app.cms.repository;

import com.mannschaft.app.cms.entity.BlogMediaR2DeleteRetryEntity;
import com.mannschaft.app.cms.entity.BlogMediaR2DeleteRetryStatus;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BlogMediaR2DeleteRetryRepository#findPendingDueAfterId} の統合テスト（Issue #2601 別任務）。
 *
 * <p>受け入れ条件:
 * AC6 next_attempt_at が未来の行は拾われない。
 * AC7 キーセットページングが境界の行を取りこぼさない（CHUNK_SIZE を跨ぐ件数を用意し、
 * 処理後も PENDING に残る行が混ざる状況で全件が処理されることを実証する）。本テストが
 * 本タスクで最も重要なテストである。</p>
 */
@DisplayName("BlogMediaR2DeleteRetryRepository#findPendingDueAfterId 統合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class BlogMediaR2DeleteRetryRepositoryIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private BlogMediaR2DeleteRetryRepository repository;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private TransactionTemplate txTemplate;

    private static final String KEY_PREFIX = "blog/TEAM/8801/2601-retry-it-";

    @AfterEach
    void cleanUpFixtures() {
        txTemplate.executeWithoutResult(status ->
                em.createNativeQuery("DELETE FROM blog_media_r2_delete_retries WHERE object_key LIKE :prefix")
                        .setParameter("prefix", KEY_PREFIX + "%")
                        .executeUpdate());
    }

    private BlogMediaR2DeleteRetryEntity insertRetry(String key, BlogMediaR2DeleteRetryStatus status,
                                                       LocalDateTime nextAttemptAt) {
        return txTemplate.execute(s -> {
            LocalDateTime now = LocalDateTime.now();
            BlogMediaR2DeleteRetryEntity entity = BlogMediaR2DeleteRetryEntity.builder()
                    .objectKey(key)
                    .objectKeyHash(com.mannschaft.app.common.util.SessionHashUtil.hash(key))
                    .fileSize(1024L)
                    .scopeType(StorageScopeType.TEAM.name())
                    .scopeId("8801")
                    .status(status)
                    .attemptCount(0)
                    .nextAttemptAt(nextAttemptAt)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            em.persist(entity);
            return entity;
        });
    }

    @Test
    @DisplayName("AC6: next_attempt_atが未来の行は拾われない")
    void 未来のnext_attempt_atは拾われない() {
        LocalDateTime now = LocalDateTime.now();
        insertRetry(KEY_PREFIX + "future.jpg", BlogMediaR2DeleteRetryStatus.PENDING, now.plusHours(1));
        BlogMediaR2DeleteRetryEntity due = insertRetry(KEY_PREFIX + "due.jpg", BlogMediaR2DeleteRetryStatus.PENDING, now.minusMinutes(1));
        em.clear();

        List<BlogMediaR2DeleteRetryEntity> result =
                repository.findPendingDueAfterId(now, null, PageRequest.of(0, 50));

        assertThat(result).extracting(BlogMediaR2DeleteRetryEntity::getId).containsExactly(due.getId());
    }

    @Test
    @DisplayName("SUCCEEDED/ABANDONEDの行は拾われない")
    void 完了済みと放棄済みは拾われない() {
        LocalDateTime now = LocalDateTime.now();
        insertRetry(KEY_PREFIX + "succeeded.jpg", BlogMediaR2DeleteRetryStatus.SUCCEEDED, now.minusMinutes(1));
        insertRetry(KEY_PREFIX + "abandoned.jpg", BlogMediaR2DeleteRetryStatus.ABANDONED, now.minusMinutes(1));
        em.clear();

        List<BlogMediaR2DeleteRetryEntity> result =
                repository.findPendingDueAfterId(now, null, PageRequest.of(0, 50));

        assertThat(result).isEmpty();
    }

    /**
     * AC7（本タスク最重要）: CHUNK_SIZE(50) を跨ぐ 120 件を投入し、ループの途中で処理済みの行が
     * 絞り込み（status=PENDING）から外れて母集合が縮んでいく状況でも、キーセットページングにより
     * 全件が取りこぼしなく処理されることを実 DB で実証する。
     *
     * <p>各チャンク取得のたびに、取得した行の一部を即座に SUCCEEDED（絞り込み対象外）へ更新することで、
     * {@code BlogMediaR2DeleteRetryBatchService} のループが実際に踏む「処理後も PENDING が残る」
     * 状況を再現する。OFFSET ページングであれば、母集合が縮んだ分だけ後続ページの先頭がずれて
     * 一部行が読み飛ばされるはずだが、キーセット（{@code id > cursor}）方式では cursor が
     * 常に「直前に読んだ最終 id」を指すため、絞り込みの増減に関わらず全件を走査できる。</p>
     */
    @Test
    @DisplayName("AC7: CHUNK_SIZEを跨ぐ件数かつ処理中に母集合が縮んでも、キーセットページングは全件を取りこぼさない")
    void キーセットページングは縮む母集合でも全件を取りこぼさない() {
        int totalCount = 120;
        int chunkSize = 50;
        LocalDateTime now = LocalDateTime.now();
        List<BlogMediaR2DeleteRetryEntity> inserted = new ArrayList<>();
        for (int i = 0; i < totalCount; i++) {
            inserted.add(insertRetry(KEY_PREFIX + "bulk-" + i + ".jpg",
                    BlogMediaR2DeleteRetryStatus.PENDING, now.minusMinutes(1)));
        }
        em.clear();

        Set<UUID> visited = new LinkedHashSet<>();
        UUID cursor = null;
        int guard = 0;
        while (guard++ < 200) {
            List<BlogMediaR2DeleteRetryEntity> chunk =
                    repository.findPendingDueAfterId(now, cursor, PageRequest.of(0, chunkSize));
            if (chunk.isEmpty()) {
                break;
            }
            for (BlogMediaR2DeleteRetryEntity entity : chunk) {
                visited.add(entity.getId());
            }
            // ループ本体を模して、取得した行の偶数番目だけ SUCCEEDED に更新する
            // （絞り込みから外れる=母集合が縮む状況を再現）。奇数番目は PENDING のまま残す。
            txTemplate.executeWithoutResult(s -> {
                for (int i = 0; i < chunk.size(); i++) {
                    if (i % 2 == 0) {
                        BlogMediaR2DeleteRetryEntity managed = em.find(BlogMediaR2DeleteRetryEntity.class, chunk.get(i).getId());
                        managed.markSucceeded(LocalDateTime.now());
                    }
                }
            });
            em.clear();

            cursor = chunk.get(chunk.size() - 1).getId();
            if (chunk.size() < chunkSize) {
                break;
            }
        }

        assertThat(visited)
                .as("全 %d 件が取りこぼしなく走査されること", totalCount)
                .hasSize(totalCount)
                .containsExactlyInAnyOrderElementsOf(
                        inserted.stream().map(BlogMediaR2DeleteRetryEntity::getId).toList());
    }
}
