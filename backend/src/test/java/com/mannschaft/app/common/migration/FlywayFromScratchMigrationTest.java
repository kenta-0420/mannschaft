package com.mannschaft.app.common.migration;

import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.gdpr.entity.AccountPurgeCompletionStatusEntity;
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
import org.hibernate.mapping.Component;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.mapping.Property;
import org.hibernate.mapping.Selectable;
import org.hibernate.mapping.ToOne;
import org.hibernate.mapping.Value;
import org.hibernate.type.BasicType;
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

import java.nio.ByteBuffer;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
            // 主キー・外部キー列は V234（CMP-260929-0654）で BINARY(16) になったため UUID_TO_BIN で入れる
            String temId = "00000000-0000-7000-8000-0000000ac801";
            String tetmId = "00000000-0000-7000-8000-0000000ac802";
            String tetmDefaultId = "00000000-0000-7000-8000-0000000ac803";
            String templateId = "00000000-0000-7000-8000-0000000ac804";
            st.executeUpdate("INSERT INTO tournament_entry_members (id, participant_id, user_id, member_number, "
                    + "sort_order, created_at, updated_at) VALUES (UUID_TO_BIN('" + temId + "'), 800803, 1, '"
                    + memberNumber + "', 0, '" + ts + "', '" + ts + "')");
            assertThat(UnpaidDriftRepaymentFixture.queryString(conn,
                    "SELECT member_number FROM tournament_entry_members WHERE id = UUID_TO_BIN('" + temId + "')"))
                    .isEqualTo(memberNumber);

            st.executeUpdate("INSERT INTO tournament_entry_template_members (id, template_id, user_id, sort_order, "
                    + "created_at, updated_at) VALUES (UUID_TO_BIN('" + tetmId + "'), UUID_TO_BIN('" + templateId
                    + "'), 1, 0, '" + ts + "', '" + ts + "')");
            assertThat(UnpaidDriftRepaymentFixture.queryLong(conn, "SELECT COUNT(*) FROM "
                    + "tournament_entry_template_members WHERE id = UUID_TO_BIN('" + tetmId + "') "
                    + "AND created_at = '" + ts + "' AND updated_at = '" + ts + "'")).isEqualTo(1L);

            // 日時を省略した INSERT でも DEFAULT で埋まる（Entity を経由しない経路の保険）
            st.executeUpdate("INSERT INTO tournament_entry_template_members (id, template_id, user_id, sort_order) "
                    + "VALUES (UUID_TO_BIN('" + tetmDefaultId + "'), UUID_TO_BIN('" + templateId + "'), 2, 0)");
            assertThat(UnpaidDriftRepaymentFixture.queryLong(conn, "SELECT COUNT(*) FROM "
                    + "tournament_entry_template_members WHERE id = UUID_TO_BIN('" + tetmDefaultId + "') "
                    + "AND created_at IS NOT NULL AND updated_at IS NOT NULL")).isEqualTo(1L);
        }
    }

    // ==================================================================
    // AC-7（CMP-260929-0654）: Entity の UUID 列の型と Flyway 実スキーマの列型の一致
    // ==================================================================

    /**
     * <b>Entity の UUID 型の列（主キーと外部キー）の DB 列型が、Hibernate が実際に書く表現と一致することを検証する。</b>
     *
     * <h2>守る不変条件</h2>
     * <p>{@link com.mannschaft.app.common.entity.UuidV7Entity} 系は Hibernate 標準の BINARY 表現（16 バイト）で書くため
     * DDL は {@code BINARY(16)} でなければならない。{@code UuidV7CharEntity} 系（{@code @JdbcTypeCode(CHAR)}）は
     * 36 文字の文字列で書くため {@code CHAR(36)} が正である。食い違うと保存・取得のたびに
     * {@code Incorrect string value} / {@code Data too long} で落ちる。
     * {@code ddl-auto=create} のテスト環境は Entity から DDL を生成するため、この食い違いは原理的に見えず、
     * 大会エントリー系 5 表 6 列（CMP-260929-0654）が長く残っていた。</p>
     *
     * <p><b>期待型の決め方</b>: 列の {@code columnDefinition} ではなく、Hibernate が UUID を書き込む際の
     * JDBC 型（{@code @JdbcTypeCode}）で決める。{@code columnDefinition = "CHAR(36)"} だけを付けて
     * JDBC 型を CHAR にし忘れた Entity は、DDL と columnDefinition が一致しても実行時に壊れるため、
     * それを見逃さないためである。</p>
     *
     * <p><b>凍結台帳（例外リスト）は作らない。</b>違反は migration か Entity を直して 0 にすること。</p>
     */
    @Test
    @Order(20)
    @DisplayName("AC-7: 全EntityのUUID列（主キー・外部キー）の型がFlyway実スキーマの binary(16) / char(36) と一致する")
    void 全EntityのUUID列の型がFlywayスキーマと一致する() throws Exception {
        migrateFromScratch();
        Map<String, Map<String, String>> actualTypes = readActualColumnTypes();

        StandardServiceRegistry registry = buildServiceRegistry();
        try {
            Metadata metadata = buildHibernateMetadata(registry);
            List<String> violations = uuidColumnTypeViolations(metadata, actualTypes);
            if (!violations.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("Entity の UUID 列と Flyway 実スキーマの列型が一致しません。\n")
                  .append("UuidV7Entity 系は binary(16)、UuidV7CharEntity 系（@JdbcTypeCode(CHAR)）は char(36) が正です。\n")
                  .append("対処は migration で列型を直す（V234 が手本。UUID_TO_BIN で既存行を保持する）か、\n")
                  .append("Entity の基底クラスを DDL に合わせること。この番人に例外を足して黙らせてはなりません。\n")
                  .append("違反一覧:\n");
                violations.stream().sorted().forEach(v -> sb.append("  ✗ ").append(v).append('\n'));
                fail(sb.toString());
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    /**
     * 番人自身の検証（負のテスト）: 実スキーマの列型を食い違わせた入力を与えると、番人が違反を返すこと。
     * 「違反 0 件」が、検査が走っていない偽 green でないことを示す。
     */
    @Test
    @Order(21)
    @DisplayName("AC-7: 負のテスト: 列型を食い違わせると番人が違反を返す（UuidV7系がchar(36)・UuidV7CharEntity系がbinary(16)）")
    void UUID列型の番人は食い違いを検出する() throws Exception {
        migrateFromScratch();
        Map<String, Map<String, String>> actualTypes = readActualColumnTypes();
        assertThat(actualTypes.get("tournament_entry_members")).as("検査対象の表が実スキーマに在ること").isNotNull();
        assertThat(actualTypes.get("user_interest_tags")).isNotNull();

        StandardServiceRegistry registry = buildServiceRegistry();
        try {
            Metadata metadata = buildHibernateMetadata(registry);
            List<String> clean = uuidColumnTypeViolations(metadata, actualTypes);
            assertThat(clean).as("前提: 歪める前は違反 0 件").isEmpty();

            // (1) UuidV7Entity 系の主キー・外部キー列を char(36) だと偽る
            Map<String, Map<String, String>> tampered = deepCopy(actualTypes);
            tampered.get("tournament_entry_members").put("id", "char(36)");
            tampered.get("tournament_entry_template_members").put("template_id", "char(36)");
            tampered.get("user_interest_tags").put("id", "char(36)");
            assertThat(uuidColumnTypeViolations(metadata, tampered))
                    .as("binary(16) が正の列を char(36) にすると違反になる")
                    .anyMatch(v -> v.startsWith("tournament_entry_members.id "))
                    .anyMatch(v -> v.startsWith("tournament_entry_template_members.template_id "))
                    .anyMatch(v -> v.startsWith("user_interest_tags.id "));

            // (2) UuidV7CharEntity 系（char(36) が正）の主キーを binary(16) だと偽る
            String charTable = tableOfFirstCharUuidEntity(metadata);
            Map<String, Map<String, String>> tampered2 = deepCopy(actualTypes);
            tampered2.get(charTable).put("id", "binary(16)");
            assertThat(uuidColumnTypeViolations(metadata, tampered2))
                    .as("char(36) が正の列（%s.id）を binary(16) にすると違反になる", charTable)
                    .anyMatch(v -> v.startsWith(charTable + ".id "));

            // (3) 文字列型（varchar(36)）も「binary(16) か char(36)」以外として違反になる
            Map<String, Map<String, String>> tampered3 = deepCopy(actualTypes);
            tampered3.get("tournament_entry_templates").put("id", "varchar(36)");
            assertThat(uuidColumnTypeViolations(metadata, tampered3))
                    .anyMatch(v -> v.startsWith("tournament_entry_templates.id "));
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    /**
     * 負のテスト: 通常の所有側 {@code @OneToOne @JoinColumn} の FK 列も検査対象であること
     * （mappedBy 側は列を所有しないため誤検出しないこと）。
     */
    @Test
    @Order(22)
    @DisplayName("AC-7: 負のテスト: 所有側 @OneToOne @JoinColumn の FK 列の型ずれを検出し、mappedBy 側は誤検出しない")
    void 所有側OneToOneのFK列も検査される() {
        StandardServiceRegistry registry = buildServiceRegistry();
        try {
            Metadata metadata = new MetadataSources(registry)
                    .addAnnotatedClass(FakeO2oParent.class)
                    .addAnnotatedClass(FakeO2oChild.class)
                    .getMetadataBuilder()
                    .applyPhysicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy())
                    .applyImplicitNamingStrategy(new SpringImplicitNamingStrategy())
                    .build();

            Map<String, Map<String, String>> ok = new HashMap<>();
            ok.put("fake_o2o_parent", new HashMap<>(Map.of("id", "binary(16)")));
            ok.put("fake_o2o_child", new HashMap<>(Map.of("id", "binary(16)", "parent_id", "binary(16)")));
            assertThat(uuidColumnTypeViolations(metadata, ok)).as("型が揃っていれば違反 0 件").isEmpty();

            Map<String, Map<String, String>> bad = deepCopy(ok);
            bad.get("fake_o2o_child").put("parent_id", "char(36)");
            assertThat(uuidColumnTypeViolations(metadata, bad))
                    .as("所有側 OneToOne の FK 列（fake_o2o_child.parent_id）の型ずれを検出する")
                    .anyMatch(v -> v.startsWith("fake_o2o_child.parent_id "));
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    /** 上記負のテスト用の検体（本番ソースセットではないため scanMappedClasses の対象外）。 */
    @jakarta.persistence.Entity
    @jakarta.persistence.Table(name = "fake_o2o_parent")
    static class FakeO2oParent extends com.mannschaft.app.common.entity.UuidV7Entity {
        /** mappedBy 側（FK 列を所有しない）。 */
        @jakarta.persistence.OneToOne(mappedBy = "parent")
        FakeO2oChild child;
    }

    @jakarta.persistence.Entity
    @jakarta.persistence.Table(name = "fake_o2o_child")
    static class FakeO2oChild extends com.mannschaft.app.common.entity.UuidV7Entity {
        /** 所有側（FK 列 parent_id を持つ）。 */
        @jakarta.persistence.OneToOne
        @jakarta.persistence.JoinColumn(name = "parent_id")
        FakeO2oParent parent;
    }

    /**
     * 全 Entity（主キー・基本属性・埋め込み・ToOne の外部キー・コレクション表）の UUID 型の列を走査し、
     * Hibernate が書き込む JDBC 型から期待される DB 列型（binary(16) / char(36)）と実スキーマを突き合わせる。
     */
    private static List<String> uuidColumnTypeViolations(Metadata metadata,
                                                         Map<String, Map<String, String>> actualTypes) {
        Set<String> violations = new java.util.TreeSet<>();
        for (PersistentClass pc : metadata.getEntityBindings()) {
            if (pc.getTable() == null || !pc.getTable().isPhysicalTable()) {
                continue;
            }
            checkUuidValue(metadata, pc.getIdentifier(), actualTypes, violations);
            for (Property property : pc.getProperties()) {
                checkUuidValue(metadata, property.getValue(), actualTypes, violations);
            }
        }
        for (org.hibernate.mapping.Collection collection : metadata.getCollectionBindings()) {
            if (collection.getCollectionTable() == null) {
                continue;
            }
            checkUuidValue(metadata, collection.getKey(), actualTypes, violations);
            checkUuidValue(metadata, collection.getElement(), actualTypes, violations);
        }
        return new ArrayList<>(violations);
    }

    private static void checkUuidValue(Metadata metadata, Value value,
                                       Map<String, Map<String, String>> actualTypes, Set<String> violations) {
        if (value == null) {
            return;
        }
        if (value instanceof Component component) {
            for (Property p : component.getProperties()) {
                checkUuidValue(metadata, p.getValue(), actualTypes, violations);
            }
            return;
        }
        Value typed = value;
        if (value instanceof ToOne toOne) {
            // 除外するのは「FK 列を所有しない側」だけ。Hibernate のマッピング上、org.hibernate.mapping.OneToOne は
            // mappedBy 側（列なし）と共有主キー（@MapsId / @PrimaryKeyJoinColumn。列は自身の主キーで検査済み）に限られる。
            // 通常の所有側 @OneToOne @JoinColumn は ManyToOne として表現され、FK 列をここで検査する。
            if (toOne instanceof org.hibernate.mapping.OneToOne || toOne.getReferencedEntityName() == null) {
                return;
            }
            PersistentClass target = metadata.getEntityBinding(toOne.getReferencedEntityName());
            if (target == null) {
                return;
            }
            typed = target.getIdentifier();
            if (typed instanceof Component) {
                return; // 複合主キーは UUID 単独列ではない
            }
        }
        String expected = expectedUuidColumnType(typed);
        if (expected == null) {
            return; // UUID 列ではない
        }
        String table = value.getTable() == null ? null : value.getTable().getName().toLowerCase(Locale.ROOT);
        if (table == null || !actualTypes.containsKey(table)) {
            return; // テーブルの存在は別の番人が守る
        }
        for (Selectable selectable : value.getSelectables()) {
            if (!(selectable instanceof Column column)) {
                continue;
            }
            String name = column.getName().toLowerCase(Locale.ROOT);
            String actual = actualTypes.get(table).get(name);
            if (actual == null) {
                continue; // 列の存在は別の番人が守る
            }
            if (!expected.equals(actual)) {
                violations.add(table + "." + name + " … 期待 " + expected + " / 実スキーマ " + actual);
            }
        }
    }

    /** UUID 型でなければ null。UUID なら Hibernate が書く JDBC 型から期待される DB 列型を返す。 */
    private static String expectedUuidColumnType(Value value) {
        if (value.getType() == null || value.getType().getReturnedClass() != java.util.UUID.class) {
            return null;
        }
        if (!(value.getType() instanceof BasicType<?> basic)) {
            return "?（BasicType ではない UUID 列）";
        }
        int code = basic.getJdbcType().getJdbcTypeCode();
        return switch (code) {
            case java.sql.Types.CHAR, java.sql.Types.VARCHAR, java.sql.Types.LONGVARCHAR -> "char(36)";
            case java.sql.Types.BINARY, java.sql.Types.VARBINARY, java.sql.Types.LONGVARBINARY,
                 org.hibernate.type.SqlTypes.UUID -> "binary(16)";
            default -> "?（JDBC 型コード " + code + "）";
        };
    }

    private static String tableOfFirstCharUuidEntity(Metadata metadata) {
        for (PersistentClass pc : metadata.getEntityBindings()) {
            if ("char(36)".equals(expectedUuidColumnType(pc.getIdentifier()))
                    && pc.getTable() != null && pc.getTable().isPhysicalTable()) {
                return pc.getTable().getName().toLowerCase(Locale.ROOT);
            }
        }
        throw new IllegalStateException("UuidV7CharEntity 系の Entity が 1 つも見つからない（負のテストの前提が崩れた）");
    }

    private static Map<String, Map<String, String>> deepCopy(Map<String, Map<String, String>> source) {
        Map<String, Map<String, String>> copy = new HashMap<>();
        source.forEach((table, columns) -> copy.put(table, new HashMap<>(columns)));
        return copy;
    }

    /** テーブル名（小文字）→ 列名（小文字）→ COLUMN_TYPE（小文字。例: binary(16) / char(36)）。 */
    private static Map<String, Map<String, String>> readActualColumnTypes() throws SQLException {
        Map<String, Map<String, String>> types = new HashMap<>();
        try (Connection conn = connect(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE "
                     + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()")) {
            while (rs.next()) {
                types.computeIfAbsent(rs.getString(1).toLowerCase(Locale.ROOT), k -> new HashMap<>())
                        .put(rs.getString(2).toLowerCase(Locale.ROOT), rs.getString(3).toLowerCase(Locale.ROOT));
            }
        }
        return types;
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    @Test
    @Order(23)
    @DisplayName("CMP1243本人設定38親の最終owner索引とusers FK撤廃、4子の同domain CASCADEを確認する")
    void personalSettingsOwnershipAndChildForeignKeys() throws SQLException {
        migrateFromScratch();
        List<String> userOwnedTables = List.of(
                "dashboard_widget_settings", "dashboard_scope_tab_order", "chat_contact_folders", "my_scope_folders",
                "user_calendar_sync_settings", "user_quick_memo_settings", "notification_settings", "user_interest_tags",
                "shared_file_stars", "contact_request_blocks", "user_action_memo_settings", "action_memo_tags",
                "point_card_user_settings", "point_card_groups", "timeline_bookmarks", "search_saved_queries",
                "appearance_settings", "user_nav_settings", "gamification_user_settings", "user_reflection_settings",
                "personal_timetable_settings", "user_blog_settings", "chat_message_bookmarks", "kb_page_favorites",
                "user_mutes", "user_favorites", "scope_member_calendar_settings", "notification_preferences",
                "notification_type_preferences", "push_subscriptions", "user_calendar_layer_settings",
                "user_weather_locations", "inbox_item_states", "notification_labels", "inbox_label_links",
                "timetable_slot_user_note_fields", "seal_scope_defaults");
        try (Connection conn = connect()) {
            List<String> absentOwnerIndexes = new ArrayList<>();
            List<String> unexpectedUsersForeignKeys = new ArrayList<>();
            for (String table : userOwnedTables) {
                if (!hasLeadingIndex(conn, table, "user_id")) {
                    absentOwnerIndexes.add(table + ".user_id");
                }
                collectUsersForeignKeys(conn, table, unexpectedUsersForeignKeys);
            }
            for (String column : List.of("blocker_id", "blocked_id")) {
                if (!hasLeadingIndex(conn, "user_blocks", column)) {
                    absentOwnerIndexes.add("user_blocks." + column);
                }
            }
            if (!hasLeadingIndex(conn, "contact_request_blocks", "blocked_id")) {
                absentOwnerIndexes.add("contact_request_blocks.blocked_id");
            }
            collectUsersForeignKeys(conn, "user_blocks", unexpectedUsersForeignKeys);
            assertThat(absentOwnerIndexes).as("本人または削除対象の関係をowner列から探索できる最終索引").isEmpty();
            assertThat(unexpectedUsersForeignKeys).as("users実DELETEでアプリ強処理の欠落が隠れない最終schema").isEmpty();
            assertCascade(conn, "chat_contact_folder_items", "folder_id", "chat_contact_folders");
            assertCascade(conn, "my_scope_folder_items", "folder_id", "my_scope_folders");
            assertCascade(conn, "action_memo_tag_links", "tag_id", "action_memo_tags");
            assertCascade(conn, "point_card_group_items", "group_id", "point_card_groups");
        }
    }

    @Test
    @Order(24)
    @DisplayName("CMP1243実Flywayでuser実DELETE後の親残存とowner親DELETEによる4子0・別owner保持を実seedで証明する")
    void personalSettingsOwnerDeleteCascadesOnlyOwnedChildren() throws SQLException {
        migrateFromScratch();
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try {
                PersonalSettingCascadeFixture target = seedSettingCascadeFixture(conn);
                PersonalSettingCascadeFixture other = seedSettingCascadeFixture(conn);
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("DELETE FROM users WHERE id = " + target.userId());
                }
                assertThat(readCascadeCounts(conn, target).values()).as("user本体DELETEだけでは4親・4子は消えない")
                        .containsOnly(1L);
                try (Statement st = conn.createStatement()) {
                    // owner-domain DELETEが発行された場合の同domain FK金型だけを証明する。
                    // 実service/AFTER_COMMITとの接続はPersonalSettingsAccountPurgeITが担う。
                    for (String table : List.of("chat_contact_folders", "my_scope_folders", "action_memo_tags", "point_card_groups")) {
                        st.executeUpdate("DELETE FROM " + table + " WHERE user_id = " + target.userId());
                    }
                }
                assertThat(readCascadeCounts(conn, target).values()).as("本人の4親DELETE後、論理親の子も含めて0")
                        .containsOnly(0L);
                assertThat(readCascadeCounts(conn, other).values()).as("別ownerの4親・4子は保持").containsOnly(1L);
            } finally {
                // 本試練のseed/DELETEだけをrollbackし、同containerの他試練fixtureを変更しない。
                conn.rollback();
            }
        }
    }

    private static boolean hasLeadingIndex(Connection conn, String table, String column) throws SQLException {
        try (ResultSet indexes = conn.getMetaData().getIndexInfo(conn.getCatalog(), null, table, false, false)) {
            while (indexes.next()) {
                if (indexes.getInt("ORDINAL_POSITION") == 1 && column.equalsIgnoreCase(indexes.getString("COLUMN_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void collectUsersForeignKeys(Connection conn, String table, List<String> foreignKeys) throws SQLException {
        try (ResultSet keys = conn.getMetaData().getImportedKeys(conn.getCatalog(), null, table)) {
            while (keys.next()) {
                if ("users".equalsIgnoreCase(keys.getString("PKTABLE_NAME"))) {
                    foreignKeys.add(table + "." + keys.getString("FKCOLUMN_NAME"));
                }
            }
        }
    }

    private static void assertCascade(Connection conn, String child, String column, String parent) throws SQLException {
        List<String> actual = new ArrayList<>();
        try (ResultSet keys = conn.getMetaData().getImportedKeys(conn.getCatalog(), null, child)) {
            while (keys.next()) {
                if (column.equalsIgnoreCase(keys.getString("FKCOLUMN_NAME"))) {
                    actual.add(keys.getString("PKTABLE_NAME") + "/" + keys.getShort("DELETE_RULE"));
                }
            }
        }
        assertThat(actual).as("%s.%s の最終FK", child, column)
                .containsExactly(parent + "/" + DatabaseMetaData.importedKeyCascade);
        assertThat(hasLeadingIndex(conn, child, column)).as("%s.%s のCASCADE探索索引", child, column).isTrue();
    }

    private record PersonalSettingCascadeFixture(long userId, long chatFolder, long scopeFolder, long tag, String group) {}

    private static PersonalSettingCascadeFixture seedSettingCascadeFixture(Connection conn) throws SQLException {
        long owner = insertGeneratedKey(conn, "INSERT INTO users (email,last_name,first_name,display_name,status,created_at,updated_at)"
                + " VALUES ('cmp1243-" + UuidV7.generate() + "@example.invalid','試練','本人','本人','ACTIVE',NOW(),NOW())");
        long chat = insertGeneratedKey(conn, "INSERT INTO chat_contact_folders (user_id,name) VALUES (" + owner + ",'私有分類')");
        long scope = insertGeneratedKey(conn, "INSERT INTO my_scope_folders (user_id,scope_type,name,deleted_at) VALUES ("
                + owner + ",'TEAM','論理削除済の私有分類',NOW())");
        long tag = insertGeneratedKey(conn, "INSERT INTO action_memo_tags (user_id,name,deleted_at) VALUES ("
                + owner + ",'論理削除済の私有タグ',NOW())");
        long memo = insertGeneratedKey(conn, "INSERT INTO action_memos (user_id,memo_date,content) VALUES ("
                + owner + ",CURRENT_DATE(),'CASCADE試練の本文')");
        String group = UuidV7.generate().toString();
        String card = UuidV7.generate().toString();
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO point_card_groups (id,user_id,name) VALUES ('" + group + "'," + owner + ",'私有分類')");
            st.executeUpdate("INSERT INTO user_point_cards (id,user_id,display_name,barcode_value) VALUES ('"
                    + card + "'," + owner + ",X'01',X'02')");
            st.executeUpdate("INSERT INTO chat_contact_folder_items (folder_id,item_type,item_id) VALUES (" + chat + ",'CONTACT'," + owner + ")");
            st.executeUpdate("INSERT INTO my_scope_folder_items (folder_id,scope_id) VALUES (" + scope + ",123)");
            st.executeUpdate("INSERT INTO action_memo_tag_links (memo_id,tag_id) VALUES (" + memo + "," + tag + ")");
            st.executeUpdate("INSERT INTO point_card_group_items (id,group_id,card_id) VALUES ('"
                    + UuidV7.generate() + "','" + group + "','" + card + "')");
        }
        return new PersonalSettingCascadeFixture(owner, chat, scope, tag, group);
    }

    private static long insertGeneratedKey(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate(sql, Statement.RETURN_GENERATED_KEYS);
            try (ResultSet keys = st.getGeneratedKeys()) {
                assertThat(keys.next()).as("所有fixtureの採番").isTrue();
                return keys.getLong(1);
            }
        }
    }

    private static Map<String, Long> readCascadeCounts(Connection conn, PersonalSettingCascadeFixture fixture) throws SQLException {
        Map<String, String> predicates = Map.of(
                "chat_contact_folders", "id = " + fixture.chatFolder(),
                "chat_contact_folder_items", "folder_id = " + fixture.chatFolder(),
                "my_scope_folders", "id = " + fixture.scopeFolder(),
                "my_scope_folder_items", "folder_id = " + fixture.scopeFolder(),
                "action_memo_tags", "id = " + fixture.tag(),
                "action_memo_tag_links", "tag_id = " + fixture.tag(),
                "point_card_groups", "id = '" + fixture.group() + "'",
                "point_card_group_items", "group_id = '" + fixture.group() + "'");
        Map<String, Long> counts = new HashMap<>();
        try (Statement st = conn.createStatement()) {
            for (Map.Entry<String, String> predicate : predicates.entrySet()) {
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + predicate.getKey() + " WHERE " + predicate.getValue())) {
                    assertThat(rs.next()).isTrue();
                    counts.put(predicate.getKey(), rs.getLong(1));
                }
            }
        }
        return counts;
    }

    @Test
    @DisplayName("GDPR CHAR64: 正式DDLで削除完了証跡の実Hibernate validateが通る")
    void 削除完了証跡のメールハッシュを正式CHAR64スキーマで検証できる() throws Exception {
        migrateFromScratch();
        assertAccountPurgeHashColumn();
        assertThatCode(() -> {
            try (SessionFactory ignored = accountPurgeValidatedSessionFactory()) {
                // SessionFactory構築時に、当Entityと正式Flywayスキーマの型を実検証する。
            }
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("GDPR CHAR64: 64桁hexをcommit後の別Sessionから完全一致で読み戻せる")
    void 削除完了証跡の64桁メールハッシュを永続化して再読込できる() throws Exception {
        migrateFromScratch();
        UUID ownedId = UuidV7.generate();
        String emailHash = "0123456789abcdef".repeat(4);
        try (SessionFactory sf = accountPurgeValidatedSessionFactory()) {
            try (Session session = sf.openSession()) {
                session.beginTransaction();
                AccountPurgeCompletionStatusEntity status = new AccountPurgeCompletionStatusEntity();
                status.setId(ownedId);
                status.setUserId(9_215_100L); // クロスドメインFKを持たない参照値。
                status.setEmailHash(emailHash);
                status.setDomainName("role");
                status.setStatus("PENDING");
                status.setAttemptedAt(LocalDateTime.of(2026, 10, 8, 13, 0));
                session.persist(status);
                session.getTransaction().commit();
            }
            try (Session session = sf.openSession()) {
                AccountPurgeCompletionStatusEntity found = session.find(AccountPurgeCompletionStatusEntity.class, ownedId);
                assertThat(found).isNotNull();
                assertThat(found.getEmailHash()).isEqualTo(emailHash).matches("[0-9a-f]{64}");
            }
            assertAccountPurgeHashColumn();
        } finally {
            // commit途中の失敗も、この試練が生成したUUIDの1行だけを後始末する。
            try (Connection connection = connect();
                 var statement = connection.prepareStatement("DELETE FROM account_purge_completion_status WHERE id = ?")) {
                statement.setBytes(1, ByteBuffer.allocate(16).putLong(ownedId.getMostSignificantBits())
                        .putLong(ownedId.getLeastSignificantBits()).array());
                statement.executeUpdate();
            }
        }
    }

    @Test
    @DisplayName("GDPR retry_count: 0・127・128・255をcommit後の別SessionとJDBCから保持できる")
    void 削除完了証跡のretry回数はunsigned上限まで保持できる() throws Exception {
        migrateFromScratch();
        UUID ownedId = UuidV7.generate();
        try (SessionFactory factory = accountPurgeValidatedSessionFactory()) {
            boolean persisted = false;
            for (int retryCount : new int[] {0, 127, 128, 255}) {
                try (Session session = factory.openSession()) {
                    session.beginTransaction();
                    if (!persisted) {
                        session.persist(accountPurgeRetryStatus(ownedId, retryCount));
                    } else {
                        session.find(AccountPurgeCompletionStatusEntity.class, ownedId).setRetryCount(retryCount);
                    }
                    session.getTransaction().commit();
                    persisted = true;
                }
                try (Session session = factory.openSession()) {
                    AccountPurgeCompletionStatusEntity found = session.find(AccountPurgeCompletionStatusEntity.class, ownedId);
                    assertThat(found).isNotNull();
                    assertThat(found.getRetryCount()).isEqualTo(retryCount);
                }
                assertThat(accountPurgeRetrySnapshot(ownedId).get("retry_count")).isEqualTo(Integer.toString(retryCount));
            }
            assertAccountPurgeRetryColumn();
        } finally {
            deleteOwnedAccountPurgeStatus(ownedId);
        }
    }

    @Test
    @DisplayName("GDPR retry_count: 256の更新拒否後もcommit済み255の全列を保持する")
    void 削除完了証跡のretry回数は範囲外更新をrollbackして既存行を保全する() throws Exception {
        migrateFromScratch();
        UUID ownedId = UuidV7.generate();
        try (SessionFactory factory = accountPurgeValidatedSessionFactory()) {
            try (Session session = factory.openSession()) {
                session.beginTransaction();
                session.persist(accountPurgeRetryStatus(ownedId, 255));
                session.getTransaction().commit();
            }
            Map<String, String> before = accountPurgeRetrySnapshot(ownedId);
            assertThat(before.get("retry_count")).isEqualTo("255");
            assertThatThrownBy(() -> {
                try (Session session = factory.openSession()) {
                    var transaction = session.beginTransaction();
                    try {
                        session.find(AccountPurgeCompletionStatusEntity.class, ownedId).setRetryCount(256);
                        session.flush();
                        transaction.commit();
                    } catch (RuntimeException error) {
                        if (transaction.isActive()) {
                            transaction.rollback();
                        }
                        throw error;
                    }
                }
            }).isInstanceOf(jakarta.persistence.PersistenceException.class).satisfies(error -> {
                Throwable cause = error;
                while (cause != null && !(cause instanceof SQLException)) {
                    cause = cause.getCause();
                }
                assertThat(cause).isInstanceOf(SQLException.class);
                SQLException sqlError = (SQLException) cause;
                assertThat(sqlError.getErrorCode()).isEqualTo(1264);
                // MySQLの範囲外拒否は22003。Connector/Jの書込みDataTruncationは22001を返す。
                assertThat(sqlError.getSQLState()).isIn("22003", "22001");
            });
            try (Session session = factory.openSession()) {
                assertThat(session.find(AccountPurgeCompletionStatusEntity.class, ownedId).getRetryCount()).isEqualTo(255);
            }
            assertThat(accountPurgeRetrySnapshot(ownedId)).isEqualTo(before);
            assertAccountPurgeRetryColumn();
        } finally {
            deleteOwnedAccountPurgeStatus(ownedId);
        }
    }

    private static AccountPurgeCompletionStatusEntity accountPurgeRetryStatus(UUID id, int retryCount) {
        AccountPurgeCompletionStatusEntity status = new AccountPurgeCompletionStatusEntity();
        status.setId(id);
        status.setUserId(9_215_101L);
        status.setEmailHash("0123456789abcdef".repeat(4));
        status.setDomainName("role");
        status.setStatus("PENDING");
        status.setAttemptedAt(LocalDateTime.of(2026, 10, 8, 13, 0));
        status.setRetryCount(retryCount);
        return status;
    }

    private static Map<String, String> accountPurgeRetrySnapshot(UUID id) throws SQLException {
        try (Connection connection = connect();
             var statement = connection.prepareStatement("SELECT HEX(id) AS id, user_id, email_hash, domain_name, status, "
                     + "attempted_at, completed_at, retry_count, last_retried_at FROM account_purge_completion_status WHERE id = ?")) {
            statement.setBytes(1, ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array());
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                Map<String, String> row = new HashMap<>();
                for (int index = 1; index <= result.getMetaData().getColumnCount(); index++) {
                    row.put(result.getMetaData().getColumnLabel(index), result.getString(index));
                }
                assertThat(result.next()).isFalse();
                return row;
            }
        }
    }

    private static void assertAccountPurgeRetryColumn() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement();
             ResultSet column = statement.executeQuery("SELECT COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.COLUMNS "
                     + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'account_purge_completion_status' AND COLUMN_NAME = 'retry_count'")) {
            assertThat(column.next()).isTrue();
            assertThat(column.getString("COLUMN_TYPE")).isEqualTo("tinyint unsigned");
            assertThat(column.getString("IS_NULLABLE")).isEqualTo("NO");
            assertThat(column.getString("COLUMN_DEFAULT")).isEqualTo("0");
            assertThat(column.next()).isFalse();
        }
    }

    private static void deleteOwnedAccountPurgeStatus(UUID id) throws SQLException {
        try (Connection connection = connect();
             var statement = connection.prepareStatement("DELETE FROM account_purge_completion_status WHERE id = ?")) {
            statement.setBytes(1, ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array());
            statement.executeUpdate();
        }
    }

    private static void assertAccountPurgeHashColumn() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement();
             ResultSet column = statement.executeQuery("SELECT COLUMN_TYPE, IS_NULLABLE FROM information_schema.COLUMNS "
                     + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'account_purge_completion_status' "
                     + "AND COLUMN_NAME = 'email_hash'")) {
            assertThat(column.next()).isTrue();
            assertThat(column.getString("COLUMN_TYPE")).isEqualTo("char(64)");
            assertThat(column.getString("IS_NULLABLE")).isEqualTo("NO");
            assertThat(column.next()).isFalse();
        }
    }

    private static SessionFactory accountPurgeValidatedSessionFactory() {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.DIALECT, MySQLDialect.class.getName())
                .applySetting(AvailableSettings.JAKARTA_JDBC_DRIVER, "com.mysql.cj.jdbc.Driver")
                .applySetting(AvailableSettings.JAKARTA_JDBC_URL, MYSQL.getJdbcUrl())
                .applySetting(AvailableSettings.JAKARTA_JDBC_USER, MYSQL.getUsername())
                .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, MYSQL.getPassword())
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "validate")
                .build();
        try {
            return new MetadataSources(registry)
                    .addAnnotatedClass(UuidV7Entity.class)
                    .addAnnotatedClass(AccountPurgeCompletionStatusEntity.class)
                    .getMetadataBuilder()
                    .applyPhysicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy())
                    .applyImplicitNamingStrategy(new SpringImplicitNamingStrategy())
                    .build().buildSessionFactory();
        } catch (RuntimeException error) {
            StandardServiceRegistryBuilder.destroy(registry);
            throw error;
        }
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
    @Test
    @Order(25)
    @DisplayName("CMP1730のUUIDv7進捗1行・singleton・cursor・retry制約が正式Flywayで成立する")
    void reservationPendingExpireStateMigrationContract() throws SQLException {
        migrateFromScratch();
        var types = readActualColumnTypes().get("reservation_pending_expire_scan_state");
        assertThat(types.get("id")).isEqualTo("binary(16)");
        assertThat(types.get("updated_at")).isEqualTo("datetime(6)");
        byte[] seedId;
        try (Connection conn = connect(); Statement statement = conn.createStatement()) {
            try (ResultSet row = statement.executeQuery("SELECT id, singleton_key, cycle_high_water, "
                    + "last_inspected_id, run_epoch, retry_primary_ids, updated_at "
                    + "FROM reservation_pending_expire_scan_state")) {
                assertThat(row.next()).isTrue();
                seedId = row.getBytes(1);
                var bytes = java.nio.ByteBuffer.wrap(seedId);
                var uuid = new java.util.UUID(bytes.getLong(), bytes.getLong());
                assertThat(uuid.version()).isEqualTo(7);
                assertThat(uuid.variant()).isEqualTo(2);
                assertThat(row.getInt(2)).isEqualTo(1);
                assertThat(row.getLong(3)).isZero();
                assertThat(row.getLong(4)).isZero();
                assertThat(row.getLong(5)).isZero();
                assertThat(row.getString(6)).isEqualTo("[]");
                assertThat(row.getTimestamp(7)).isNotNull();
                assertThat(row.next()).isFalse();
            }
            try (ResultSet row = statement.executeQuery("SELECT TABLE_COLLATION FROM information_schema.TABLES "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reservation_pending_expire_scan_state'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("utf8mb4_0900_ai_ci");
            }
            // この試験が行う有効/不正更新はrollbackし、正式seedを他の相乗り試験へ保全する。
            conn.setAutoCommit(false);
            try {
                for (String sql : List.of(
                        "UPDATE reservation_pending_expire_scan_state SET singleton_key = 2",
                        "UPDATE reservation_pending_expire_scan_state SET last_inspected_id = 1",
                        "UPDATE reservation_pending_expire_scan_state SET run_epoch = -1",
                        "UPDATE reservation_pending_expire_scan_state SET retry_primary_ids = '{}'",
                        "INSERT INTO reservation_pending_expire_scan_state SELECT "
                                + "UNHEX('01999999777770008000000000001730'), singleton_key, cycle_high_water, "
                                + "last_inspected_id, run_epoch, retry_primary_ids, updated_at "
                                + "FROM reservation_pending_expire_scan_state")) {
                    org.assertj.core.api.Assertions.assertThatThrownBy(() -> statement.executeUpdate(sql))
                            .isInstanceOf(SQLException.class);
                }
                String fiveHundred = java.util.stream.LongStream.rangeClosed(1, 500).mapToObj(Long::toString)
                        .collect(java.util.stream.Collectors.joining(",", "[", "]"));
                try (var update = conn.prepareStatement("UPDATE reservation_pending_expire_scan_state "
                        + "SET retry_primary_ids = ?")) {
                    update.setString(1, fiveHundred);
                    assertThat(update.executeUpdate()).isEqualTo(1);
                    update.setString(1, fiveHundred.substring(0, fiveHundred.length() - 1) + ",501]");
                    org.assertj.core.api.Assertions.assertThatThrownBy(update::executeUpdate)
                            .isInstanceOf(SQLException.class);
                }
            } finally {
                conn.rollback();
            }
        }
        migrateFromScratch();
        try (Connection conn = connect(); Statement statement = conn.createStatement();
             ResultSet row = statement.executeQuery("SELECT id, retry_primary_ids FROM reservation_pending_expire_scan_state")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getBytes(1)).containsExactly(seedId);
            assertThat(row.getString(2)).isEqualTo("[]");
            assertThat(row.next()).isFalse();
        }
    }

}
