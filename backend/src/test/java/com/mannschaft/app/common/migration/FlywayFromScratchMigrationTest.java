package com.mannschaft.app.common.migration;

import com.mannschaft.app.circulation.RecipientStatus;
import com.mannschaft.app.circulation.entity.CirculationRecipientEntity;
import com.mannschaft.app.committee.entity.CommitteeDistributionLogEntity;
import com.mannschaft.app.committee.entity.ConfirmationMode;
import com.mannschaft.app.committee.entity.DistributionScope;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Converter;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.MappedSuperclass;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.dialect.MySQLDialect;
import org.hibernate.mapping.Column;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>fresh（まっさら）な MySQL に対し、全 Flyway マイグレーションがバージョン順で
 * 最後まで成功すること</b>、および<b>その実スキーマが全 Entity のマッピングを満たすこと</b>を
 * 検証する番人テスト。
 *
 * <h2>このテストが守る不変条件</h2>
 * <p>本番・staging・CI・新規開発環境のいずれも、初回構築時は空の DB に対して
 * Flyway がマイグレーションを<b>バージョン昇順</b>で適用する。
 * したがって「後発バージョンのマイグレーションが作成するカラム / テーブルを、
 * 先発バージョンのマイグレーションが参照する」という<b>順序逆転</b>があると、
 * fresh setup が途中で失敗してアプリが起動できない。</p>
 *
 * <p>さらに、Flyway が構築し終えたスキーマが Entity のマッピングを満たしていなければ、
 * アプリは起動できても ORM が {@code Unknown column} で全滅する。
 * こちらを守るのが {@link #全Entityのマッピング列がFlywayスキーマに存在する()} である。</p>
 *
 * <h2>なぜ既存テストで検出できなかったか</h2>
 * <p>本プロジェクトの通常の統合テスト環境（{@code src/test/resources/application-test.yml}）は
 * {@code spring.jpa.hibernate.ddl-auto=create} + {@code spring.flyway.enabled=false} で動作する。
 * すなわちテスト DB のスキーマは <b>Entity から生成</b>され、<b>Flyway マイグレーションは一切実行されない</b>。
 * このため Flyway マイグレーションの順序逆転も、Flyway スキーマと Entity の乖離も、
 * 通常のテストでは原理的に永遠に検出できない構造だった。
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
 * <h2>Flyway 実スキーマを要する番人テストの相乗り先</h2>
 * <p>MySQL コンテナ起動 + 全マイグレーション適用は CI 時間の主要コストである。
 * そのため「Flyway 実スキーマを必要とする番人テスト」は<b>本クラスに相乗りさせ、
 * コンテナ起動と migrate を 1 回に集約する</b>方針とする
 * （新しいクラスを作るとコンテナが 1 個増える）。
 * 各テストメソッドは実行順に依存しないよう、冪等な {@link #migrateFromScratch()} を先頭で呼ぶ
 * （2 回目以降の migrate は適用済みのため実質 no-op）。</p>
 *
 * <p>Docker 未起動環境では {@code @EnabledIf} によりスキップされる。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// from-scratch 適用（@Order(1)）を先に走らせ、その実スキーマを番人テスト（@Order(2)）が使う。
// 順序を固定しないと「migrationsExecuted が正であること」の検証が実行順に依存して壊れる。
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIf("com.mannschaft.app.common.migration.FlywayFromScratchMigrationTest#isDockerAvailable")
@DisplayName("Flyway from-scratch 全マイグレーション順序適用テスト")
class FlywayFromScratchMigrationTest {

    /** Entity 走査の起点パッケージ。 */
    private static final String ENTITY_BASE_PACKAGE = "com.mannschaft.app";

    /**
     * <b>既知の未返済ドリフト台帳 — 空であること（2026-09-29 全額返済・CMP-260924-0010）</b>
     *
     * <p>この台帳は<b>空でなければならない</b>。Entity に列を足したら同じ PR で
     * migration（{@code ALTER TABLE ... ADD COLUMN}）も足すこと。
     * 番人が赤くなったとき、ここへ列を<b>追記して黙らせてはならない</b>。
     * 凍結は技術的負債であり免罪符ではない（2026-07-28 に 22 件を凍結して導入したが、
     * 凍結された列は本番相当環境で {@code Unknown column} により当該 API を 500 にし続けていた）。
     * 増やさず、migration で返済せよ。</p>
     *
     * <p>返済の履歴（左が Hibernate が発行する列名、括弧内が Flyway 実列名）:</p>
     * <ul>
     *   <li><b>命名戦略の数字 / 末尾大文字の罠（旧 11 件・2026-08-20 に Issue #2856 で全額返済）</b> —
     *       {@code s3Key} → {@code s3key} / {@code positionX} → {@code positionx} /
     *       {@code r2ObjectKey} → {@code r2object_key} / {@code alertSent30d} → {@code alert_sent30d}。
     *       いずれも Entity 側に {@code @Column(name=...)} を明示して是正済みのため台帳から削除した。
     *       再発は {@code common.architecture.EntityDigitBoundaryColumnNameGuardTest}（静的走査）と
     *       {@code common.schema.EntityDigitBoundaryColumnFlywaySchemaIT}（実 Flyway スキーマ）が防ぐ。</li>
     *   <li><b>{@code circulation_recipients} の {@code skip_reason / skipped_by / skipped_at}
     *       （旧 3 件・2026-09-29 に CMP-260924-0010 で返済）</b> —
     *       V9.175 のコメントは「V9.171 で追加済み」と書いているが、
     *       V9.171 は {@code create_name_disclosure_change_logs} で無関係。実際にはどこにも存在しなかった。
     *       V230（{@code add_skip_columns_to_circulation_recipients}）で列を追加した。</li>
     *   <li><b>{@code tournament_entry_members.member_number}・
     *       {@code tournament_entry_template_members.created_at/updated_at}（旧 4 件・うち
     *       {@code content_reports.content_hidden} は 2026-09-22 に CMP-260920-0705 で返済、
     *       残る 3 件は 2026-09-29 に CMP-260924-0010 で返済）</b> —
     *       {@code queue_tickets.guest_phone} と同型（Entity にだけ足して migration を忘れた）。
     *       {@code content_reports.content_hidden} は本番相当環境で
     *       {@code Unknown column 'cre1_0.content_hidden'} により運営の通報一覧
     *       {@code GET /api/v1/admin/moderation/reports} が常時 500 になっており、
     *       V220 で列を追加して台帳から削除した。残る 3 件は V230 で列を追加した。</li>
     *   <li><b>{@code shift_budget_allocations} の {@code *_uq}（旧 3 件・2026-09-09 に返済）</b> —
     *       Entity が {@code @GeneratedColumn} で生成カラムを宣言していたが、Flyway（V11.030）は
     *       MySQL 8.0 の制約（FK ベースカラムに STORED 生成カラム不可、Error 3192）により
     *       関数インデックスで同じ一意制約を実装しており、列そのものが存在しなかった。
     *       Entity 側の宣言を撤去して是正済み（実機で {@code Unknown column 'deleted_at_uq'} により
     *       F08.7 シフト予算 API が全て 500 になっていた）。
     *       DB 側の一意性が残っていることは
     *       {@link #shift_budget_allocationsの一意性が関数インデックスで担保されている()} が守る。</li>
     *   <li><b>{@code BaseEntity} の {@code created_at} / {@code updated_at}（旧 16 件・
     *       2026-09-29 に CMP-260924-0010 で返済。凍結時の Javadoc は「17 件」と書いていたが実数は 16）</b> —
     *       {@link com.mannschaft.app.common.BaseEntity} は全継承 Entity に
     *       {@code createdAt} / {@code updatedAt} を持たせ、{@code @PrePersist} /
     *       {@code @PreUpdate} で必ず書き込むが、これらのテーブルの CREATE TABLE は
     *       片方または両方を作っていなかった。V230（{@code add_missing_base_entity_timestamps}）で
     *       列を追加し、既存行は元の日時列（{@code created_at} / {@code recorded_at} / {@code voted_at}）
     *       から埋め戻した。{@code committee_distribution_logs} の Entity が
     *       {@code updated_at} を {@code @AttributeOverride} で書き込み不可にしていた回避策も撤去した。</li>
     * </ul>
     *
     * <p>返済 migration の既存データ経路・冪等性・Entity との整合は、本クラスの「返済AC-*」テストと
     * {@link UnpaidDriftRepaymentFixture}（migrate 中の Callback）が守る。</p>
     */
    private static final Set<String> KNOWN_UNPAID_DRIFT = Set.of();

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
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
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
    @Order(1)
    @DisplayName("全マイグレーションをバージョン順に適用_例外なく最後まで完了する")
    void 全マイグレーションがバージョン順で最後まで成功する() {
        // when: 全マイグレーションを適用（順序逆転があればここで例外）
        MigrateResult result = migrateFromScratch();

        // then: 1 件以上が適用され、最後まで成功している
        assertThat(result.success).as("全マイグレーションが成功すること").isTrue();
        assertThat(result.migrationsExecuted)
                .as("fresh DB なので 1 件以上のマイグレーションが適用されること")
                .isPositive();
    }

    /**
     * <b>Flyway が構築した実スキーマが、全 Entity のマッピング列を備えていることを検証する。</b>
     *
     * <h2>守る不変条件</h2>
     * <p>Entity にフィールドを足したのに migration を書き忘れると、Flyway で構築した環境
     * （本番 / staging / 新規開発環境）で Hibernate が存在しない列を含む SQL を発行し、
     * {@code Unknown column} で当該ドメインの API が全滅する。
     * {@code ddl-auto=create} のテスト環境では Entity からスキーマが生成されるため
     * この乖離は原理的に見えず、実際に {@code queue_tickets.guest_phone} が
     * 4 ヶ月間気付かれずに残っていた（2026-03-23 追加 → 2026-07-28 発見）。</p>
     *
     * <h2>なぜ {@code ddl-auto=validate} をそのまま使わないのか</h2>
     * <p>Hibernate の {@code SchemaValidator} は列の<b>型</b>も突き合わせるため、
     * {@code BIGINT UNSIGNED} / 桁数違いなど「ORM は壊れないが定義がずれている」差分まで
     * 検出し、720 超のテーブルに対して大量のノイズを生む。
     * さらに最初の 1 件で例外を投げて打ち切るため、負債の全量を一度に把握できない。</p>
     *
     * <p>本テストはそこで、Hibernate 自身が構築した {@link Metadata}
     * （＝ Spring Boot と同じ {@link CamelCaseToUnderscoresNamingStrategy} /
     * {@link SpringImplicitNamingStrategy} を適用した、権威ある物理名）を使い、
     * <b>テーブル・列の「存在」だけ</b>を全件突き合わせる。
     * これは本番を壊す事故（{@code Unknown column}）を過不足なく捕らえつつ、
     * 型ノイズを持ち込まず、違反を一度に全件列挙できる。</p>
     *
     * <h2>Spring コンテキストは起動しない</h2>
     * <p>{@code MetadataSources} を直接組み立てるため {@code @SpringBootTest} は不要。
     * MySQL コンテナも本クラスのものを再利用するため、本テストの追加による
     * CI コスト増は「Entity 走査 + メタデータ構築 + JDBC メタデータ読み取り」のみで、
     * コンテナ起動も Spring コンテキスト起動も増えない。</p>
     */
    @Test
    @Order(2)
    @DisplayName("全Entityのマッピング列がFlyway実スキーマに存在する（Unknown column 事故の番人）")
    void 全Entityのマッピング列がFlywayスキーマに存在する() throws Exception {
        // given: Flyway 実スキーマ（単独実行にも耐えるよう冪等に再適用。適用済みなら no-op）
        migrateFromScratch();
        Map<String, Set<String>> actualSchema = readActualSchema();

        StandardServiceRegistry registry = buildServiceRegistry();
        try {
            Metadata metadata = buildHibernateMetadata(registry);

            // when: Entity 由来の物理テーブル / 列を実スキーマと突き合わせる
            List<String> violations = new ArrayList<>();
            for (Namespace namespace : metadata.getDatabase().getNamespaces()) {
                for (org.hibernate.mapping.Table table : namespace.getTables()) {
                    // @Subselect / ビューマッピング（例: RepairFundBalanceView）は実テーブルではない
                    if (!table.isPhysicalTable()) {
                        continue;
                    }
                    String tableName = table.getName().toLowerCase(Locale.ROOT);
                    Set<String> actualColumns = actualSchema.get(tableName);
                    if (actualColumns == null) {
                        violations.add(tableName + " … テーブルが Flyway スキーマに存在しない");
                        continue;
                    }
                    for (Column column : table.getColumns()) {
                        String key = tableName + "." + column.getName().toLowerCase(Locale.ROOT);
                        if (!actualColumns.contains(column.getName().toLowerCase(Locale.ROOT))
                                && !KNOWN_UNPAID_DRIFT.contains(key)) {
                            violations.add(key);
                        }
                    }
                }
            }

            // then: 凍結済みの既知ドリフト以外は 1 件も存在しない
            if (!violations.isEmpty()) {
                violations.sort(String::compareTo);
                StringBuilder sb = new StringBuilder();
                sb.append("Entity がマップしている列 / テーブルが Flyway スキーマに存在しません。\n")
                  .append("Flyway で構築した環境（本番・staging・新規開発環境）では Hibernate が\n")
                  .append("存在しない列を含む SQL を発行し、Unknown column で当該ドメインの API が全滅します。\n")
                  .append("対処は次のいずれか（ドメインの設計判断）:\n")
                  .append("  (a) Flyway に ALTER TABLE ... ADD COLUMN の migration を追加する\n")
                  .append("      （採番規約は CLAUDE.md / backend/.claudecode.md §18）\n")
                  .append("  (b) 実列名が既にある場合は Entity 側に @Column(name = \"...\") を明示する\n")
                  .append("      （Spring の命名戦略は s3Key → s3key のように数字の直後を区切らない）\n")
                  .append("違反一覧:\n");
                for (String v : violations) {
                    sb.append("  ✗ ").append(v).append('\n');
                }
                fail(sb.toString());
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    /**
     * <b>{@code shift_budget_allocations} の一意性が実 DB 上に残っていることを検証する。</b>
     *
     * <p>Entity から {@code @UniqueConstraint}（生成カラム参照）を撤去した是正（2026-09-09）により、
     * この表の一意性は <b>Flyway の関数インデックス {@code uq_sba_scope_category_period} だけ</b>が
     * DB 側の担保となった。Entity には表現手段が無いため、誰かが移行から
     * この索引を落としても Java 側では何も壊れず、
     * 「同一スコープの割当が二重に作られる」事故が静かに発生しうる。
     * そこで実スキーマ上に UNIQUE 索引が存在し、NULL-safe 化の COALESCE 式を
     * 7 要素すべてについて持つことを直接検査する。</p>
     *
     * <p>アプリ層の重複防止（{@code ShiftBudgetAllocationService.findLiveByScope} の
     * {@code SELECT ... FOR UPDATE}）は併存する二重化であり、本索引の代替ではない。</p>
     */
    @Test
    @Order(3)
    @DisplayName("shift_budget_allocations の一意性が関数インデックスで担保されている")
    void shift_budget_allocationsの一意性が関数インデックスで担保されている() throws Exception {
        // given: Flyway 実スキーマ（単独実行にも耐えるよう冪等に再適用）
        migrateFromScratch();

        String createTable;
        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             java.sql.Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SHOW CREATE TABLE shift_budget_allocations")) {
            assertThat(rs.next()).as("shift_budget_allocations が存在すること").isTrue();
            createTable = rs.getString(2);
        }

        // 式インデックスは SHOW CREATE TABLE 上で改行・空白が入りうるため、空白を潰して比較する
        String normalized = createTable.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);

        assertThat(normalized)
                .as("UNIQUE 索引 uq_sba_scope_category_period が存在すること（DDL: %s）", createTable)
                .contains("uniquekey`uq_sba_scope_category_period`");
        assertThat(normalized)
                .as("team_id の NULL-safe 化（COALESCE 番兵値）が索引に含まれること（DDL: %s）", createTable)
                .contains("coalesce(`team_id`,0)");
        assertThat(normalized)
                .as("project_id の NULL-safe 化が索引に含まれること（DDL: %s）", createTable)
                .contains("coalesce(`project_id`,0)");
        assertThat(normalized)
                .as("deleted_at の NULL-safe 化が索引に含まれること（DDL: %s）", createTable)
                .contains("coalesce(`deleted_at`,");
    }

    // ==================================================================
    // 凍結台帳 22 列の返済 migration（V230・CMP-260924-0010）の番人
    // 既存データ経路の検査は migrate 中の Callback（UnpaidDriftRepaymentFixture）が行い、
    // ここでは AC ごとにその結果を報告する。最終スキーマを要する検査はここで直接行う。
    // ==================================================================

    @Test
    @Order(10)
    @DisplayName("返済AC-3: 返済22列の型・長さ・NULL可否がEntityマッピングと整合する")
    void 返済列の型と長さとNULL可否がEntityと整合する() throws Exception {
        migrateFromScratch();
        try (Connection conn = connect()) {
            assertThat(UnpaidDriftRepaymentFixture.columnDefinitionViolations(conn))
                    .as("返済 22 列の定義違反").isEmpty();
        }
    }

    @Test
    @Order(11)
    @DisplayName("返済AC-4: 既存行の updated_at は created_at から・出欠の created_at は recorded_at から・proxy_votes は voted_at から埋め戻される")
    void 返済migrationで既存行の新列が元の日時列から埋め戻される() throws Throwable {
        migrateFromScratch();
        UnpaidDriftRepaymentFixture.assertPassed("AC-4");
    }

    @Test
    @Order(12)
    @DisplayName("返済AC-5a: 途中まで列が足された状態から完遂し、V230 の3本を再実行しても成功して列定義と値が変わらない")
    void 返済migrationは途中状態からも再実行でも完遂する() throws Throwable {
        migrateFromScratch();
        UnpaidDriftRepaymentFixture.assertPassed("AC-5a");
    }

    @Test
    @Order(13)
    @DisplayName("返済AC-5b: 途中状態で既に在った列の番兵値は返済 migration で上書きされない")
    void 返済migrationは既に在った列の値を上書きしない() throws Throwable {
        migrateFromScratch();
        UnpaidDriftRepaymentFixture.assertPassed("AC-5b");
    }

    @Test
    @Order(14)
    @DisplayName("返済AC-6: committee_distribution_logs を Entity で保存→取得でき、更新で updated_at が進む")
    void 委員会伝達ログをEntityで保存取得更新できる() throws Exception {
        migrateFromScratch();
        // 構造: updated_at を書き込み不可にしていた回避策（@AttributeOverride）が撤去されていること
        assertThat(CommitteeDistributionLogEntity.class.getAnnotation(AttributeOverrides.class))
                .as("CommitteeDistributionLogEntity に @AttributeOverrides が残っていないこと").isNull();
        assertThat(CommitteeDistributionLogEntity.class.getAnnotation(AttributeOverride.class))
                .as("CommitteeDistributionLogEntity に @AttributeOverride が残っていないこと").isNull();

        try (SessionFactory sf = sessionFactory(CommitteeDistributionLogEntity.class)) {
            Long id;
            try (Session s = sf.openSession()) {
                s.beginTransaction();
                CommitteeDistributionLogEntity log = CommitteeDistributionLogEntity.builder()
                        .committeeId(1L)
                        .contentType("CUSTOM_MESSAGE")
                        .customTitle("返済検証")
                        .targetScope(DistributionScope.COMMITTEE_ONLY)
                        .announcementEnabled(false)
                        .confirmationMode(ConfirmationMode.NONE)
                        .createdBy(1L)
                        .build();
                s.persist(log);
                s.getTransaction().commit();
                id = log.getId();
            }
            assertThat(id).isNotNull();

            LocalDateTime firstUpdatedAt;
            try (Session s = sf.openSession()) {
                CommitteeDistributionLogEntity found = s.find(CommitteeDistributionLogEntity.class, id);
                assertThat(found).as("findById で取得できること").isNotNull();
                assertThat(found.getUpdatedAt()).as("updated_at が読めること").isNotNull();
                firstUpdatedAt = found.getUpdatedAt();
            }
            try (Connection conn = connect()) {
                assertThat(UnpaidDriftRepaymentFixture.queryLong(conn,
                        "SELECT COUNT(*) FROM committee_distribution_logs WHERE id = " + id
                                + " AND updated_at = created_at"))
                        .as("保存時に Entity が updated_at を created_at と同じ値で書き込むこと").isEqualTo(1L);
            }

            Thread.sleep(1_500); // DATETIME は秒精度のため 1 秒以上空ける

            try (Session s = sf.openSession()) {
                s.beginTransaction();
                s.find(CommitteeDistributionLogEntity.class, id).applyGeneratedIds("[1]", null);
                s.getTransaction().commit();
            }
            try (Session s = sf.openSession()) {
                CommitteeDistributionLogEntity found = s.find(CommitteeDistributionLogEntity.class, id);
                assertThat(found.getAnnouncementFeedIds()).isEqualTo("[1]");
                assertThat(found.getUpdatedAt()).as("更新で updated_at が進むこと").isAfter(firstUpdatedAt);
            }
        }
    }

    @Test
    @Order(15)
    @DisplayName("返済AC-7a: 本人スキップは skipped_at だけを保存し skip_reason / skipped_by は null")
    void 本人スキップはskipped_atのみ保存される() throws Exception {
        migrateFromScratch();
        try (SessionFactory sf = sessionFactory(CirculationRecipientEntity.class)) {
            Long id = persistRecipient(sf, 800_701L);
            try (Session s = sf.openSession()) {
                s.beginTransaction();
                s.find(CirculationRecipientEntity.class, id).skip();
                s.getTransaction().commit();
            }
            try (Session s = sf.openSession()) {
                CirculationRecipientEntity r = s.find(CirculationRecipientEntity.class, id);
                assertThat(r.getStatus()).isEqualTo(RecipientStatus.SKIPPED);
                assertThat(r.getSkippedAt()).as("skipped_at が保存されること").isNotNull();
                assertThat(r.getSkipReason()).as("本人スキップは skip_reason が null").isNull();
                assertThat(r.getSkippedBy()).as("本人スキップは skipped_by が null").isNull();
            }
        }
    }

    @Test
    @Order(16)
    @DisplayName("返済AC-7b: 管理者スキップは skip_reason / skipped_by / skipped_at の3列すべてを保存する")
    void 管理者スキップは3列すべて保存される() throws Exception {
        migrateFromScratch();
        try (SessionFactory sf = sessionFactory(CirculationRecipientEntity.class)) {
            Long id = persistRecipient(sf, 800_702L);
            try (Session s = sf.openSession()) {
                s.beginTransaction();
                s.find(CirculationRecipientEntity.class, id).adminSkip(1L, "長期不在のため");
                s.getTransaction().commit();
            }
            try (Session s = sf.openSession()) {
                CirculationRecipientEntity r = s.find(CirculationRecipientEntity.class, id);
                assertThat(r.getStatus()).isEqualTo(RecipientStatus.SKIPPED);
                assertThat(r.getSkipReason()).isEqualTo("長期不在のため");
                assertThat(r.getSkippedBy()).isEqualTo(1L);
                assertThat(r.getSkippedAt()).isNotNull();
            }
        }
    }

    @Test
    @Order(17)
    @DisplayName("返済AC-7c: 返済前から在る回覧受信者行の skip 系3列は null のまま、Entity でも読める")
    void 既存の回覧受信者行はskip系3列がnullのまま読める() throws Throwable {
        migrateFromScratch();
        // V230 適用直後に、返済前にシードした行で検査済み（シード行は検査後に削除される）
        UnpaidDriftRepaymentFixture.assertPassed("AC-7c");

        // 最終スキーマでも、返済前の列だけで書かれた行（skip 系 3 列を知らない INSERT）を Entity で読める
        long id;
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("SET FOREIGN_KEY_CHECKS = 0");
            st.executeUpdate("INSERT INTO circulation_recipients (document_id, user_id, sort_order, status, "
                    + "tilt_angle, is_flipped) VALUES (800703, 1, 0, 'PENDING', 0, 0)");
            id = UnpaidDriftRepaymentFixture.queryLong(conn,
                    "SELECT id FROM circulation_recipients WHERE document_id = 800703");
        }
        try (SessionFactory sf = sessionFactory(CirculationRecipientEntity.class);
             Session s = sf.openSession()) {
            CirculationRecipientEntity r = s.find(CirculationRecipientEntity.class, id);
            assertThat(r).as("既存形の行を Entity で読めること").isNotNull();
            assertThat(r.getSkipReason()).isNull();
            assertThat(r.getSkippedBy()).isNull();
            assertThat(r.getSkippedAt()).isNull();
        }
    }

    @Test
    @Order(18)
    @DisplayName("返済AC-8: proxy_votes / daily_attendance_records / tournament_entry_members / tournament_entry_template_members に Entity と同じ列で INSERT・SELECT できる")
    void 返済対象テーブルにEntityと同じ列でINSERTとSELECTができる() throws Exception {
        migrateFromScratch();
        String ts = "2024-05-06 07:08:09";
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("SET FOREIGN_KEY_CHECKS = 0");

            st.executeUpdate("INSERT INTO proxy_votes (motion_id, user_id, vote_type, is_proxy_vote, delegation_id, "
                    + "voted_at, created_at, updated_at) VALUES (800801, 1, 'APPROVE', 0, NULL, "
                    + "'" + ts + "', '" + ts + "', '" + ts + "')");
            assertThat(UnpaidDriftRepaymentFixture.queryLong(conn, "SELECT COUNT(*) FROM proxy_votes "
                    + "WHERE motion_id = 800801 AND created_at = '" + ts + "' AND updated_at = '" + ts + "'"))
                    .isEqualTo(1L);

            st.executeUpdate("INSERT INTO daily_attendance_records (team_id, student_user_id, attendance_date, status, "
                    + "recorded_by, recorded_at, created_at, updated_at) VALUES (800802, 1, '2024-05-06', 'ATTENDING', 1, "
                    + "'" + ts + "', '" + ts + "', '" + ts + "')");
            assertThat(UnpaidDriftRepaymentFixture.queryLong(conn, "SELECT COUNT(*) FROM daily_attendance_records "
                    + "WHERE team_id = 800802 AND created_at = '" + ts + "' AND updated_at = '" + ts + "'"))
                    .isEqualTo(1L);

            String memberNumber = "M".repeat(50); // Entity の length=50 いっぱい
            st.executeUpdate("INSERT INTO tournament_entry_members (id, participant_id, user_id, member_number, "
                    + "sort_order, created_at, updated_at) VALUES ('ac8-tem', 800803, 1, '" + memberNumber + "', 0, "
                    + "'" + ts + "', '" + ts + "')");
            assertThat(UnpaidDriftRepaymentFixture.queryString(conn,
                    "SELECT member_number FROM tournament_entry_members WHERE id = 'ac8-tem'"))
                    .isEqualTo(memberNumber);

            st.executeUpdate("INSERT INTO tournament_entry_template_members (id, template_id, user_id, sort_order, "
                    + "created_at, updated_at) VALUES ('ac8-tetm', 'ac8-template', 1, 0, '" + ts + "', '" + ts + "')");
            assertThat(UnpaidDriftRepaymentFixture.queryLong(conn, "SELECT COUNT(*) FROM "
                    + "tournament_entry_template_members WHERE id = 'ac8-tetm' "
                    + "AND created_at = '" + ts + "' AND updated_at = '" + ts + "'")).isEqualTo(1L);

            // 日時を省略した INSERT でも DEFAULT で埋まる（Entity を経由しない経路の保険）
            st.executeUpdate("INSERT INTO tournament_entry_template_members (id, template_id, user_id, sort_order) "
                    + "VALUES ('ac8-tetm-default', 'ac8-template', 2, 0)");
            assertThat(UnpaidDriftRepaymentFixture.queryLong(conn, "SELECT COUNT(*) FROM "
                    + "tournament_entry_template_members WHERE id = 'ac8-tetm-default' "
                    + "AND created_at IS NOT NULL AND updated_at IS NOT NULL")).isEqualTo(1L);
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static SessionFactory sessionFactory(Class<?> entity) {
        return UnpaidDriftRepaymentFixture.buildSessionFactory(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword(), entity);
    }

    private static Long persistRecipient(SessionFactory sf, long documentId) {
        try (Session s = sf.openSession()) {
            s.beginTransaction();
            CirculationRecipientEntity recipient = CirculationRecipientEntity.builder()
                    .documentId(documentId)
                    .userId(1L)
                    .build();
            s.persist(recipient);
            s.getTransaction().commit();
            return recipient.getId();
        }
    }

    /**
     * 本番の fresh 構築と同条件（out-of-order 無効）で全マイグレーションを適用する。冪等。
     *
     * <p>{@link UnpaidDriftRepaymentFixture} を Callback として登録し、V230（返済 migration）の
     * 直前に既存行をシード・直後に既存データ経路を検査する（初回の from-scratch 適用でだけ発火する）。</p>
     */
    private static MigrateResult migrateFromScratch() {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .callbacks(new UnpaidDriftRepaymentFixture())
                .load();
        return flyway.migrate();
    }

    /**
     * Flyway 適用後の実スキーマを JDBC メタデータから読み取る。
     *
     * @return テーブル名（小文字）→ 列名（小文字）の集合
     */
    private static Map<String, Set<String>> readActualSchema() throws SQLException {
        Map<String, Set<String>> schema = new HashMap<>();
        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getColumns(conn.getCatalog(), null, "%", "%")) {
                while (rs.next()) {
                    String table = rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT);
                    String column = rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT);
                    schema.computeIfAbsent(table, k -> new HashSet<>()).add(column);
                }
            }
        }
        return schema;
    }

    /**
     * Hibernate のサービスレジストリを構築する。
     *
     * <p>実 MySQL への接続情報を渡すことで方言解決を本番と同条件にする
     * （方言も明示指定して JDBC メタデータ取得に失敗した場合の揺れを排除する）。</p>
     */
    private static StandardServiceRegistry buildServiceRegistry() {
        return new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.DIALECT, MySQLDialect.class.getName())
                .applySetting(AvailableSettings.JAKARTA_JDBC_DRIVER, "com.mysql.cj.jdbc.Driver")
                .applySetting(AvailableSettings.JAKARTA_JDBC_URL, MYSQL.getJdbcUrl())
                .applySetting(AvailableSettings.JAKARTA_JDBC_USER, MYSQL.getUsername())
                .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, MYSQL.getPassword())
                .build();
    }

    /**
     * 全 Entity / MappedSuperclass / Embeddable / Converter を走査して Hibernate メタデータを構築する。
     *
     * <p>命名戦略は Spring Boot の既定（{@code application.yml} でも上書きしていない）と
     * 同一のものを明示適用する。これにより「Hibernate が実際に発行する物理列名」を
     * 権威ある形で得られる。Spring Boot 3.5 の {@code HibernateProperties.Naming} の既定は
     * physical = {@link CamelCaseToUnderscoresNamingStrategy}（Hibernate 本体のクラス。
     * Spring の {@code SpringPhysicalNamingStrategy} が Hibernate へ移管されたもの）、
     * implicit = {@link SpringImplicitNamingStrategy} である。</p>
     */
    private static Metadata buildHibernateMetadata(StandardServiceRegistry registry)
            throws ClassNotFoundException {
        MetadataSources sources = new MetadataSources(registry);
        for (Class<?> mappedClass : scanMappedClasses()) {
            sources.addAnnotatedClass(mappedClass);
        }
        return sources.getMetadataBuilder()
                .applyPhysicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy())
                .applyImplicitNamingStrategy(new SpringImplicitNamingStrategy())
                .build();
    }

    /**
     * {@code com.mannschaft.app} 配下の JPA マッピング対象クラス（本番ソースセットのみ）を走査する。
     *
     * <p>テストソースセットにも番人メタテスト用のダミー Entity
     * （{@code DummyD6ExposedEntity} 等）が存在する。これらは Flyway に対応テーブルを持たないのが
     * 正しい姿であり、本テストの対象は本番の Entity のみであるため、
     * クラスの出所（{@code build/classes/java/test}）で機械的に除外する。
     * 台帳に載せて握りつぶすのではなく、そもそも検査対象から外すのが筋である。</p>
     */
    private static List<Class<?>> scanMappedClasses() throws ClassNotFoundException {
        // 既定の isCandidateComponent は「具象かつ独立したクラス」だけを通すため、
        // abstract な @MappedSuperclass（BaseEntity / UuidV7Entity）が漏れる。全件通すよう上書きする。
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                        return true;
                    }
                };
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(MappedSuperclass.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Embeddable.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Converter.class));

        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(ENTITY_BASE_PACKAGE)) {
            String className = definition.getBeanClassName();
            if (className == null) {
                continue;
            }
            Class<?> mappedClass = Class.forName(className);
            if (!isFromTestSourceSet(mappedClass)) {
                classes.add(mappedClass);
            }
        }
        assertThat(classes)
                .as("com.mannschaft.app 配下の JPA マッピングクラスが走査できること")
                .isNotEmpty();
        return classes;
    }

    /** クラスがテストソースセット（{@code build/classes/java/test}）由来かどうかを判定する。 */
    private static boolean isFromTestSourceSet(Class<?> clazz) {
        java.security.ProtectionDomain domain = clazz.getProtectionDomain();
        if (domain == null || domain.getCodeSource() == null
                || domain.getCodeSource().getLocation() == null) {
            return false; // 出所不明なら本番扱い（検査する側に倒す）
        }
        String location = domain.getCodeSource().getLocation().getPath();
        return location.contains("/classes/java/test");
    }
}
