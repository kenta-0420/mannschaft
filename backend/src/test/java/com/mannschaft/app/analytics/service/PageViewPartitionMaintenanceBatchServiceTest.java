package com.mannschaft.app.analytics.service;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F10.8 ページビュー生ログ パーティション保守バッチの結合テスト（Testcontainers MySQL 8.0）。
 *
 * <p>{@link PageViewPartitionMaintenanceBatchService#addPartitionIfNeeded} が
 * 翌々月分の月次パーティションを {@code p_future} の再オーガナイズで追加すること（AC-21）を、
 * 実 MySQL の {@code information_schema.PARTITIONS} を SELECT して検証する。</p>
 *
 * <h2>テストの前提整備（ddl-auto: create との整合）</h2>
 * <p>test プロファイルは {@code ddl-auto: create} のため {@code page_view_logs} は Hibernate 生成の
 * <b>非パーティション</b>テーブルになる。本バッチはパーティションテーブルを前提とするため、
 * {@code @BeforeEach} で当該テーブルを DROP し、Flyway 相当のパーティション付き DDL で再作成する
 * （生ログ INSERT を伴わないためスキーマだけあれば足りる）。</p>
 */
@DisplayName("PageView パーティション保守バッチ 結合テスト (F10.8)")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class PageViewPartitionMaintenanceBatchServiceTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private PageViewPartitionMaintenanceBatchService batchService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void recreatePartitionedTable() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS page_view_logs");
        // 当月・翌月のみを持つ最小のパーティション付きテーブル（翌々月は本バッチが追加する対象）。
        YearMonth now = YearMonth.now();
        YearMonth next = now.plusMonths(1);
        String nowBoundary = boundary(now.plusMonths(1)); // 当月パーティションの上限 = 翌月1日
        String nextBoundary = boundary(next.plusMonths(1)); // 翌月パーティションの上限 = 翌々月1日
        String sql = "CREATE TABLE page_view_logs ("
                + "  id BINARY(16) NOT NULL,"
                + "  scope_type VARCHAR(20) NOT NULL,"
                + "  scope_id BIGINT UNSIGNED NOT NULL,"
                + "  content_type VARCHAR(20) NOT NULL,"
                + "  content_id BIGINT UNSIGNED NOT NULL DEFAULT 0,"
                + "  url VARCHAR(512) NOT NULL,"
                + "  title VARCHAR(255) NOT NULL,"
                + "  user_id BIGINT UNSIGNED NULL,"
                + "  visitor_id CHAR(36) NOT NULL,"
                + "  viewed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "  PRIMARY KEY (id, viewed_at)"
                + ") ENGINE=InnoDB "
                + "PARTITION BY RANGE (TO_DAYS(viewed_at)) ("
                + "  PARTITION " + partitionName(now) + " VALUES LESS THAN (TO_DAYS('" + nowBoundary + "')),"
                + "  PARTITION " + partitionName(next) + " VALUES LESS THAN (TO_DAYS('" + nextBoundary + "')),"
                + "  PARTITION p_future VALUES LESS THAN MAXVALUE"
                + ")";
        jdbcTemplate.execute(sql);
    }

    // @AfterEach で page_view_logs を DROP しない: 共有 ApplicationContext（singleton container）を
    // 使う他テストクラス（集計・集計取得・リスナー）が同一 DB の page_view_logs を INSERT/SELECT するため、
    // 本クラス終了後も有効な（パーティション付き）テーブルを残す。ddl-auto:create は起動時 1 回のみで
    // テスト間に再生成されないため、DROP して放置すると後続クラスが「テーブル不在」で落ちる。

    @Test
    @DisplayName("AC-21: addPartitionIfNeeded で翌々月分パーティションが追加される")
    void addNextNextMonthPartition() {
        YearMonth nextNext = YearMonth.now().plusMonths(2);
        String partitionName = partitionName(nextNext);

        // 事前: 翌々月パーティションは存在しない
        assertThat(partitionCount(partitionName)).isZero();

        batchService.addPartitionIfNeeded(nextNext);

        // 事後: 翌々月パーティションが存在する（p_future の手前に挿入される）
        assertThat(partitionCount(partitionName)).isEqualTo(1);
        assertThat(partitionCount("p_future")).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-21 冪等: 既存パーティションに対して再実行しても二重追加されない")
    void addPartitionIfNeeded_idempotent() {
        YearMonth nextNext = YearMonth.now().plusMonths(2);
        String partitionName = partitionName(nextNext);

        batchService.addPartitionIfNeeded(nextNext);
        batchService.addPartitionIfNeeded(nextNext);

        assertThat(partitionCount(partitionName)).isEqualTo(1);
    }

    private int partitionCount(String partitionName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.PARTITIONS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'page_view_logs' "
                        + "AND PARTITION_NAME = ?",
                Integer.class, partitionName);
        return count != null ? count : 0;
    }

    private String partitionName(YearMonth ym) {
        return String.format("p_%d_%02d", ym.getYear(), ym.getMonthValue());
    }

    private String boundary(YearMonth ym) {
        return ym.getYear() + "-" + String.format("%02d", ym.getMonthValue()) + "-01";
    }
}
