package com.mannschaft.app.common.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>fresh（まっさら）な MySQL に対し、全 Flyway マイグレーションがバージョン順で
 * 最後まで成功すること</b>を検証する番人テスト。
 *
 * <h2>このテストが守る不変条件</h2>
 * <p>本番・staging・CI・新規開発環境のいずれも、初回構築時は空の DB に対して
 * Flyway がマイグレーションを<b>バージョン昇順</b>で適用する。
 * したがって「後発バージョンのマイグレーションが作成するカラム / テーブルを、
 * 先発バージョンのマイグレーションが参照する」という<b>順序逆転</b>があると、
 * fresh setup が途中で失敗してアプリが起動できない。</p>
 *
 * <h2>なぜ既存テストで検出できなかったか</h2>
 * <p>本プロジェクトの通常の統合テスト環境（{@code src/test/resources/application-test.yml}）は
 * {@code spring.jpa.hibernate.ddl-auto=create} + {@code spring.flyway.enabled=false} で動作する。
 * すなわちテスト DB のスキーマは <b>Entity から生成</b>され、<b>Flyway マイグレーションは一切実行されない</b>。
 * このため Flyway マイグレーションの順序逆転は通常のテストでは永遠に検出できない構造だった。
 * 実際に V3.147（{@code todos.linked_shift_slot_id}）が V13.014 で追加される
 * {@code linked_schedule_id} を {@code AFTER} 句で先行参照していたバグが長らく隠れ、
 * 既存環境では {@code spring.flyway.out-of-order=true} によって偶然回避されてきた。</p>
 *
 * <h2>本テストの方針</h2>
 * <p>上記の盲点を塞ぐため、本テストは Spring コンテキストを起動せず、Testcontainers の
 * 実 MySQL 8.0 に対して {@link Flyway} を Java API で直接実行する。
 * {@code outOfOrder(false)}（＝本番の fresh 構築と同条件）で
 * {@code classpath:db/migration} の全マイグレーションを適用し、例外なく完了することを検証する。</p>
 *
 * <p>{@code @SpringBootTest} を使わないのは、それを使うと上記の
 * {@code application-test.yml}（{@code flyway.enabled=false}）が効いてしまい、
 * 本テストの目的（実 Flyway 実行）が達成できないためである。</p>
 *
 * <p>Docker 未起動環境では {@code @EnabledIf} によりスキップされる。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.common.migration.FlywayFromScratchMigrationTest#isDockerAvailable")
@DisplayName("Flyway from-scratch 全マイグレーション順序適用テスト")
class FlywayFromScratchMigrationTest {

    /**
     * fresh DB 検証用の MySQL コンテナ。
     *
     * <p>{@code --log_bin_trust_function_creators=1} を付与している理由:
     * V13.045 などが {@code CREATE TRIGGER} を含むが、MySQL 8.0 はバイナリログ有効
     * （{@code log_bin=ON}）かつ接続ユーザーに SUPER 権限が無い場合、
     * {@code log_bin_trust_function_creators=OFF}（デフォルト）だと
     * <b>Error 1419（SUPER 権限が無くトリガ/関数を作成できない）</b>で失敗する。
     * 本番・dev の MySQL（AWS RDS 含む。RDS では SUPER を付与できないため
     * パラメータグループで {@code log_bin_trust_function_creators=1} を設定するのが定石）
     * では当該設定によりトリガ作成が許可されており、現に dev DB には
     * {@code trg_rss_block_update_after_lock} 等のトリガが存在する。
     * Testcontainers のデフォルト（非 root ユーザー + binlog ON + trust OFF）は
     * 本番より厳しくトリガ作成を拒否してしまうため、本番と同条件に揃える。
     * これは順序逆転検証（本テストの目的）と無関係な権限差異による偽陽性を排除するための
     * 環境忠実化であり、症状の握りつぶしではない。</p>
     */
    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_fromscratch")
            .withUsername("test")
            .withPassword("test")
            .withCommand("--log_bin_trust_function_creators=1");

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeAll
    void startContainer() {
        MYSQL.start();
    }

    @AfterAll
    void stopContainer() {
        MYSQL.stop();
    }

    /**
     * fresh な MySQL に対し、全マイグレーションをバージョン昇順（out-of-order 無効）で適用する。
     *
     * <p>順序逆転（後発オブジェクトへの先行参照）が 1 件でもあれば、
     * 該当マイグレーションで {@link org.flywaydb.core.api.exception.FlywayException} が送出され、
     * 本テストは失敗する。これにより fresh setup の破綻を恒久的に検知する。</p>
     */
    @Test
    @DisplayName("全マイグレーションをバージョン順に適用_例外なく最後まで完了する")
    void 全マイグレーションがバージョン順で最後まで成功する() {
        // given: 本番 fresh 構築と同条件の Flyway 設定（out-of-order 無効）
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();

        // when: 全マイグレーションを適用（順序逆転があればここで例外）
        MigrateResult result = flyway.migrate();

        // then: 1 件以上が適用され、最後まで成功している
        assertThat(result.success).as("全マイグレーションが成功すること").isTrue();
        assertThat(result.migrationsExecuted)
                .as("fresh DB なので 1 件以上のマイグレーションが適用されること")
                .isPositive();
    }
}
