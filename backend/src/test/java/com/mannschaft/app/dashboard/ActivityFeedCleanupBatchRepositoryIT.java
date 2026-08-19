package com.mannschaft.app.dashboard;

import com.mannschaft.app.dashboard.entity.ActivityFeedEntity;
import com.mannschaft.app.dashboard.repository.ActivityFeedRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F03.18 §10.5: {@link ActivityFeedRepository#deleteByCreatedAtBefore} の永続化検証（AC-26）。
 *
 * <p>{@code ActivityFeedEntity#createdAt} は {@code @PrePersist} で {@code LocalDateTime.now()}
 * を書き込むため（{@code updatable = false}）、「古いレコード」を作るには保存後に JDBC で
 * {@code created_at} を直接書き換える（実運用でも古い行はすべて過去に INSERT された行であり、
 * この手順は実データの状態を模擬する）。</p>
 *
 * <p>{@code @Modifying} クエリ（{@code deleteByCreatedAtBefore}）はトランザクション必須のため
 * クラスに {@code @Transactional} を付ける（本番の呼び出し元 {@code ActivityFeedCleanupBatchService}
 * も {@code @Transactional} を付けている）。</p>
 *
 * <p>Docker 未起動環境では {@code @EnabledIf} により全体 skip（CI で実行される）。</p>
 */
@DisplayName("ActivityFeedRepository.deleteByCreatedAtBefore 統合テスト（F03.18 AC-26）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Transactional
class ActivityFeedCleanupBatchRepositoryIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private ActivityFeedRepository activityFeedRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ActivityFeedEntity.ActivityFeedEntityBuilder<?, ?> baseEntity(String summary) {
        return ActivityFeedEntity.builder()
                .scopeType(ScopeType.TEAM)
                .scopeId(20L)
                .actorId(2L)
                .activityType(ActivityType.POST_CREATED)
                .targetType(TargetType.TIMELINE_POST)
                .targetId(99999L)
                .summary(summary);
    }

    /** 保存後に {@code created_at} を直接書き換える（updatable=false のため JDBC 経由）。 */
    private void backdate(Long id, LocalDateTime createdAt) {
        jdbcTemplate.update("UPDATE activity_feed SET created_at = ? WHERE id = ?", createdAt, id);
    }

    @Test
    @DisplayName("AC-26: 閾値より古い行のみ物理削除し、新しい行は残る")
    void deleteByCreatedAtBefore_removesOnlyOlderRows() {
        // Given: 閾値（31日前）より古い行と、新しい行（1日前）をそれぞれ用意する
        LocalDateTime threshold = LocalDateTime.now().minusDays(30);

        ActivityFeedEntity oldEntity = activityFeedRepository.saveAndFlush(
                baseEntity("31日前の古い行").build());
        backdate(oldEntity.getId(), LocalDateTime.now().minusDays(31));

        ActivityFeedEntity freshEntity = activityFeedRepository.saveAndFlush(
                baseEntity("1日前の新しい行").build());
        backdate(freshEntity.getId(), LocalDateTime.now().minusDays(1));

        // When
        int deletedCount = activityFeedRepository.deleteByCreatedAtBefore(threshold);

        // Then: 古い行だけが消え、新しい行は残る
        // JDBC で直接カウントする（Hibernate 1次キャッシュ経由の findById だと、
        // バルク delete が persistence context に反映されず消えたはずの行が
        // 見えてしまう罠があるため、素の SQL で物理削除を検証する）。
        assertThat(deletedCount).as("削除件数は少なくとも1件（古い行）").isGreaterThanOrEqualTo(1);

        Integer oldCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM activity_feed WHERE id = ?", Integer.class, oldEntity.getId());
        assertThat(oldCount).as("31日前の行は物理削除されている").isZero();

        Integer freshCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM activity_feed WHERE id = ?", Integer.class, freshEntity.getId());
        assertThat(freshCount).as("1日前の行は削除されず残っている").isEqualTo(1);
    }

    @Test
    @DisplayName("AC-26: 対象0件でも例外を投げず0を返す")
    void deleteByCreatedAtBefore_noRows_returnsZero() {
        LocalDateTime farPastThreshold = LocalDateTime.of(2000, 1, 1, 0, 0);

        Integer preExistingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM activity_feed WHERE created_at < ?", Integer.class, farPastThreshold);
        assertThat(preExistingCount).as("この閾値より古い行が残っていないことを前提とする").isZero();

        int deletedCount = activityFeedRepository.deleteByCreatedAtBefore(farPastThreshold);

        assertThat(deletedCount).isZero();
    }
}
