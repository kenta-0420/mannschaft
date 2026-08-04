package com.mannschaft.app.common.migration;

import com.mannschaft.app.common.persistence.probe.UnsignedIdProbeEntity;
import com.mannschaft.app.common.persistence.probe.UnsignedIdProbeRepository;
import jakarta.persistence.Converter;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.MappedSuperclass;
import org.assertj.core.api.SoftAssertions;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
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
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.math.BigInteger;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.sql.DataSource;

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
     * <b>既知の未返済ドリフト台帳（2026-07-28 凍結）</b>
     *
     * <p>{@code queue_tickets.guest_phone} の欠落（本 PR で是正）を調査した際、
     * 同型の乖離が他ドメインにも既に存在することが判明した。
     * これらは各々「Entity 側に {@code @Column(name=...)} を足す」のか
     * 「Flyway 側に列を足す」のかがドメインごとの設計判断になるため、
     * 本 PR では是正せず<b>凍結して番人を先に導入する</b>。</p>
     *
     * <p>凍結は技術的負債であり免罪符ではない。<b>このリストは増やしてはならない</b>
     * （新規ドリフトは即座に fail させるのが本テストの存在意義）。
     * 返済のたびに該当行を削除し、最終的に空にすること。</p>
     *
     * <p>乖離の内訳（左が Hibernate が発行する列名、括弧内が Flyway 実列名）:</p>
     * <ul>
     *   <li><b>{@code s3Key} / {@code positionX} / {@code r2ObjectKey} 系（8 件）</b> —
     *       Spring Boot の {@code CamelCaseToUnderscoresNamingStrategy} は
     *       「小文字の直後の大文字」でのみ {@code _} を挿入するため、
     *       数字の直後の大文字（{@code s3Key} → {@code s3key}）や末尾の大文字
     *       （{@code positionX} → {@code positionx}）では区切られない。
     *       Flyway 側は {@code s3_key} / {@code position_x} / {@code r2_object_key} で作られている。
     *       {@code ProxyInputConsentEntity} は同じ罠を踏んで
     *       {@code @Column(name = "scanned_document_s3_key")} で是正済み（同じ対処が正解）。</li>
     *   <li><b>{@code notification_credit_purchases}（2 件）</b> —
     *       Flyway 実列は {@code alert_sent_30d} / {@code alert_sent_7d} だが、
     *       Entity フィールド {@code alertSent30d} は {@code alert_sent30d} にマップされる。</li>
     *   <li><b>{@code circulation_recipients}（3 件）</b> —
     *       V9.175 のコメントは「V9.171 で追加済み」と書いているが、
     *       V9.171 は {@code create_name_disclosure_change_logs} で無関係。実際にはどこにも存在しない。</li>
     *   <li><b>{@code content_reports.content_hidden}・{@code tournament_entry_members.member_number}・
     *       {@code tournament_entry_template_members.created_at/updated_at}（4 件）</b> —
     *       {@code queue_tickets.guest_phone} と同型（Entity にだけ足して migration を忘れた）。</li>
     *   <li><b>{@code shift_budget_allocations} の {@code *_uq}（3 件）</b> —
     *       Entity は {@code @GeneratedColumn} で生成カラムを宣言しているが、
     *       Flyway（V11.030）は生成カラムではなく関数インデックスで同じ一意制約を実装しており、
     *       列そのものが存在しない。</li>
     *   <li><b>{@code BaseEntity} の {@code created_at} / {@code updated_at}（17 件）</b> —
     *       {@link com.mannschaft.app.common.BaseEntity} は全継承 Entity に
     *       {@code createdAt} / {@code updatedAt} を持たせ、{@code @PrePersist} /
     *       {@code @PreUpdate} で必ず書き込むが、これらのテーブルの CREATE TABLE は
     *       片方または両方を作っていない。</li>
     * </ul>
     */
    private static final Set<String> KNOWN_UNPAID_DRIFT = Set.of(
        // --- 命名戦略の数字/末尾大文字の罠（Entity 側に @Column(name=...) を足すのが正解）---
        "chart_photos.s3key",                                 // 実列: s3_key
        "data_exports.s3key",                                 // 実列: s3_key
        "direct_mail_image_uploads.s3key",                    // 実列: s3_key
        "equipment_items.s3key",                              // 実列: s3_key
        "kb_image_uploads.s3key",                             // 実列: s3_key
        "resident_documents.s3key",                           // 実列: s3_key
        "corkboard_groups.positionx",                         // 実列: position_x
        "corkboard_groups.positiony",                         // 実列: position_y
        "timetable_slot_user_note_attachments.r2object_key",  // 実列: r2_object_key
        "notification_credit_purchases.alert_sent30d",        // 実列: alert_sent_30d
        "notification_credit_purchases.alert_sent7d",         // 実列: alert_sent_7d
        // --- migration そのものが存在しない（Flyway 側に列を足すのが正解）---
        "circulation_recipients.skip_reason",
        "circulation_recipients.skipped_by",
        "circulation_recipients.skipped_at",
        "content_reports.content_hidden",
        "tournament_entry_members.member_number",
        "tournament_entry_template_members.created_at",
        "tournament_entry_template_members.updated_at",
        // --- 生成カラム vs 関数インデックスの設計不一致（要設計判断）---
        "shift_budget_allocations.team_id_uq",
        "shift_budget_allocations.project_id_uq",
        "shift_budget_allocations.deleted_at_uq",
        // --- BaseEntity の created_at / updated_at を CREATE TABLE が作っていない ---
        "ad_conversions.updated_at",
        "analytics_alert_history.updated_at",
        "attendance_transition_alerts.updated_at",
        "budget_transaction_attachments.updated_at",
        "chart_body_marks.updated_at",
        "chart_photos.updated_at",
        "committee_distribution_logs.updated_at",
        "daily_attendance_records.created_at",
        "job_check_ins.updated_at",
        "line_message_logs.updated_at",
        "onboarding_step_completions.updated_at",
        "parking_applications.updated_at",
        "period_attendance_records.created_at",
        "proxy_votes.created_at",
        "proxy_votes.updated_at",
        "webhook_event_subscriptions.updated_at"
    );

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
     * <b>issue #2545: ネイティブクエリのスカラ型は {@code BIGINT UNSIGNED} 列に対して何を返すのか</b>を
     * 本番同一の Flyway 実スキーマ上で実測し、恒久的に固定する。
     *
     * <h2>背景（訂正された前提）</h2>
     * <p>PR #2514 は「MySQL Connector/J は符号なし BIGINT を {@link java.math.BigInteger} で返すので、
     * ネイティブクエリの射影に {@code Long} と書くとテストでは通り本番だけ落ちる」と javadoc に断定した。
     * しかし<b>この機構は一度も実測されていなかった</b>（出典はその javadoc のみで、
     * テスト・IT・障害記録に {@code BigInteger} 起因の実測は無い）。
     * 現スタックは Spring Boot 3.5 系 ＝ Hibernate ORM 6.6 系であり、
     * ネイティブクエリのスカラ型解決が Hibernate 5 系（{@code getColumnClassName()} 経由で
     * {@code BigInteger}）とは異なる可能性があった。</p>
     *
     * <h2>なぜ通常のテストでは決着しないか</h2>
     * <p>{@code src/test/resources/application-test.yml} は {@code ddl-auto=create} /
     * {@code flyway.enabled=false} であり、テスト DB の ID 列は Entity の {@code Long} 由来で
     * <b>符号付き</b> {@code bigint} になる。符号性が本番と違うのだから、
     * 符号性に由来する型差は原理的にテストに現れない。
     * 本テストは Flyway 実スキーマ（{@code users.id} は {@code BIGINT UNSIGNED}）上で走る唯一の
     * 経路であり、ここでしか実測できない。</p>
     *
     * <h2>測る 4 経路</h2>
     * <ol>
     *   <li>生 JDBC（{@code ResultSet#getObject}）</li>
     *   <li>Hibernate のネイティブクエリ（{@code EntityManager#createNativeQuery}）のスカラ</li>
     *   <li>Spring Data の {@code @Query(nativeQuery = true)} で {@code List<Long>} を宣言したときの<b>要素</b>の実行時型
     *       （{@code QueryExecutionResultHandler} はコレクション型は変換するが要素型は変換しない、という仮説）</li>
     *   <li>射影インタフェースに {@code Long} と書いたとき（{@code ProjectingMethodInterceptor} →
     *       {@code DefaultConversionService} の {@code NumberToNumber} で救われる、という仮説）</li>
     * </ol>
     *
     * <p>実測結果は下の assert がそのまま結論である。ここが赤くなったら
     * 「ドライバ / Hibernate / Spring Data の型解決が変わった」という重大な事実であり、
     * issue #2545 の是正方針を丸ごと見直す必要がある。</p>
     */
    @Test
    @Order(3)
    @DisplayName("BIGINT UNSIGNED 列のネイティブクエリ射影の実行時型を実測する（issue #2545）")
    void ネイティブクエリの符号なしBIGINT射影の実行時型を固定する() throws Exception {
        // given: Flyway 実スキーマ（冪等。単独実行にも耐える）
        migrateFromScratch();

        // given: users.id が本当に BIGINT UNSIGNED であること（前提そのものの検算）
        String usersIdColumnType = readColumnType("users", "id");
        assertThat(usersIdColumnType)
                .as("本実測の前提: Flyway 実スキーマの users.id は符号なし BIGINT であること")
                .isEqualTo("bigint unsigned");

        // given: 1 行だけ用意する（NOT NULL かつ default 無しの列をダミーで充填）
        ensureProbeRow("users");

        Object viaJdbc;
        Object viaHibernateNative;
        Object viaSpringDataListElement;
        Object viaProjection;
        Object viaSpringDataObjectArray;
        Object viaJdbcTemplateTyped;

        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             java.sql.Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM users ORDER BY id LIMIT 1")) {
            assertThat(rs.next()).as("実測用の users 行が存在すること").isTrue();
            viaJdbc = rs.getObject(1);
        }

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(ProbeJpaConfig.class);
            ctx.refresh();

            EntityManagerFactory emf = ctx.getBean(EntityManagerFactory.class);
            try (EntityManager em = emf.createEntityManager()) {
                viaHibernateNative = em
                        .createNativeQuery("SELECT id FROM users ORDER BY id LIMIT 1")
                        .getSingleResult();
            }

            UnsignedIdProbeRepository repository = ctx.getBean(UnsignedIdProbeRepository.class);

            List<Long> ids = repository.findIdsAsLongList();
            assertThat(ids).as("List<Long> 経路が 1 件返すこと").hasSize(1);
            // 宣言型は List<Long> だが、実行時の要素が本当に Long かは未知。
            // 型消去のため List<Object> 経由で取り出さないと ClassCastException で
            // 「何が入っていたか」を報告できずに落ちる。
            viaSpringDataListElement = ((List<?>) ids).get(0);

            List<UnsignedIdProbeRepository.IdProjection> projections = repository.findIdsAsProjection();
            assertThat(projections).as("射影インタフェース経路が 1 件返すこと").hasSize(1);
            viaProjection = invokeProjectionGetter(projections.get(0));

            List<Object[]> rows = repository.findIdAndCountAsObjectArray();
            assertThat(rows).as("List<Object[]> 経路が 1 件返すこと").hasSize(1);
            viaSpringDataObjectArray = rows.get(0)[0];

            // 宣言型 List<Long> の get(0) は javac が checkcast を挿入するため、
            // List<?> 経由で受けないと「何が入っていたか」を報告する前に落ちる。
            List<?> jdbcTemplateRows = new JdbcTemplate(ctx.getBean(DataSource.class))
                    .queryForList("SELECT id FROM users ORDER BY id LIMIT 1", Long.class);
            viaJdbcTemplateTyped = jdbcTemplateRows.get(0);
        }

        // then: 全経路の実行時型を固定する（1 回の実行で全経路を報告するため soft assert）
        SoftAssertions softly = new SoftAssertions();

        // [1] だけが BigInteger である。ドライバは確かに符号なし BIGINT を BigInteger で返す。
        // つまり #2514 の「ドライバの挙動」の記述自体は正しかった。
        softly.assertThat(viaJdbc)
                .as("[1] 生 JDBC ResultSet#getObject は BIGINT UNSIGNED を BigInteger で返す "
                        + "(MySQL Connector/J の仕様。ここが型ズレの発生源)")
                .isInstanceOf(BigInteger.class);

        // [2]〜[5] は Hibernate 6 が ResultSetMetaData#getColumnType（BIGINT）で
        // 型を解決し、JdbcType が Long へ正規化するため、BigInteger は ORM 境界を越えてこない。
        // ＝ #2514 が恐れた「射影に Long と書くと本番だけ落ちる」機構は現行スタックでは成立しない。
        softly.assertThat(viaHibernateNative)
                .as("[2] Hibernate ネイティブクエリのスカラは Long に正規化される "
                        + "(Hibernate 6 は getColumnClassName ではなく getColumnType で解決する)")
                .isInstanceOf(Long.class);
        softly.assertThat(viaSpringDataListElement)
                .as("[3] Spring Data @Query(nativeQuery=true) の List<Long> の『要素』も Long "
                        + "(QueryExecutionResultHandler が要素型を変換しないのは事実だが、"
                        + "そもそも Hibernate 層で既に Long になっているため問題化しない)")
                .isInstanceOf(Long.class);
        softly.assertThat(viaProjection)
                .as("[4] 射影インタフェースの Long 宣言も Long")
                .isInstanceOf(Long.class);
        softly.assertThat(viaSpringDataObjectArray)
                .as("[5] List<Object[]> の要素も Long "
                        + "(AdSegmentService の (Long) row[0] 直キャストは本番でも落ちない)")
                .isInstanceOf(Long.class);
        softly.assertThat(viaJdbcTemplateTyped)
                .as("[6] JdbcTemplate#queryForList(sql, Long.class) は "
                        + "SingleColumnRowMapper の NumberUtils 変換で Long になる "
                        + "(型を指定しない queryForList(sql) は変換が効かず BigInteger が漏れる点に注意)")
                .isInstanceOf(Long.class);
        softly.assertAll();
    }

    /**
     * <b>issue #2545 の鏡像: 符号付き列と符号なし列を素の {@code =} で JOIN できることを実測する。</b>
     *
     * <p>{@code MyScopeFolderItemRepository#aggregateFolderUnreadCounts} には
     * {@code ON CAST(n.scope_id AS UNSIGNED) = item.scope_id} という JOIN 条件があった。
     * しかし本番 DDL では</p>
     * <ul>
     *   <li>{@code notifications.scope_id} … {@code BIGINT UNSIGNED}（V4.019）</li>
     *   <li>{@code my_scope_folder_items.scope_id} … 符号付き {@code BIGINT}（V9.101）</li>
     * </ul>
     * <p>であり、{@code CAST(n.scope_id AS UNSIGNED)} は<b>既に符号なしの列を符号なしへ変換する no-op</b>で、
     * 効果は「{@code idx_notifications_scope} を使えなくする」ことだけだった
     * （インデックス列に関数を適用すると sargable でなくなる）。
     * 一方 {@code ddl-auto=create} のテスト環境では両側とも符号付きになるため、
     * この CAST の有無は従来のテストでは一切観測できなかった。</p>
     *
     * <p>本テストは「符号付き BIGINT と符号なし BIGINT を CAST 無しの {@code =} で
     * 突き合わせても正しく一致する」ことを Flyway 実スキーマ上で実測し、
     * CAST 撤去が挙動を変えないことを恒久的に固定する。
     * ID は AUTO_INCREMENT の非負値であり、MySQL の符号付き→符号なし変換は
     * 非負域では厳密であるため一致は保たれる。</p>
     */
    @Test
    @Order(4)
    @DisplayName("符号付き BIGINT と符号なし BIGINT は CAST 無しで JOIN 一致する（issue #2545 の鏡像）")
    void 符号性の異なるBIGINT同士がCASTなしで一致する() throws Exception {
        // given: Flyway 実スキーマ（冪等）
        migrateFromScratch();

        // given: 前提となる符号性の非対称が実在すること
        assertThat(readColumnType("notifications", "scope_id"))
                .as("notifications.scope_id は符号なし").isEqualTo("bigint unsigned");
        assertThat(readColumnType("my_scope_folder_items", "scope_id"))
                .as("my_scope_folder_items.scope_id は符号付き").isEqualTo("bigint");

        // given: 双方に同じ scope_id を持つ行を 1 件ずつ
        ensureProbeRow("notifications");
        ensureProbeRow("my_scope_folder_items");
        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             java.sql.Statement st = conn.createStatement()) {
            // 検証対象は「符号性の異なる BIGINT 同士の比較」だけであり、
            // 参照整合性は無関係。ダミー行の folder_id は実在しないため FK 検査を外す
            // （握りつぶしではなく、フィクスチャの対象外項目の明示的な除外）。
            st.execute("SET FOREIGN_KEY_CHECKS = 0");
            st.executeUpdate("UPDATE notifications SET scope_id = 4242");
            st.executeUpdate("UPDATE my_scope_folder_items SET scope_id = 4242");

            // when / then: CAST 有り・無しのどちらも同じ 1 件に一致する
            String joinSql = "SELECT COUNT(*) FROM my_scope_folder_items item "
                    + "JOIN notifications n ON %s = item.scope_id";
            try (ResultSet rs = st.executeQuery(String.format(joinSql, "CAST(n.scope_id AS UNSIGNED)"))) {
                rs.next();
                assertThat(rs.getInt(1)).as("CAST 有り（撤去前の形）で 1 件一致すること").isEqualTo(1);
            }
            try (ResultSet rs = st.executeQuery(String.format(joinSql, "n.scope_id"))) {
                rs.next();
                assertThat(rs.getInt(1))
                        .as("CAST 無し（撤去後の形）でも同じく 1 件一致すること。"
                                + "ここが 0 なら CAST 撤去は挙動を変えており、撤去してはならない")
                        .isEqualTo(1);
            }
        }
    }

    /** 射影インタフェースの getter を反射で呼ぶ（戻り値の宣言型 Long でのキャストを避け、実行時型を素で観測するため）。 */
    private static Object invokeProjectionGetter(Object projection) throws Exception {
        return UnsignedIdProbeRepository.IdProjection.class.getMethod("getId").invoke(projection);
    }

    /**
     * 実測用の最小 JPA コンテキスト。
     *
     * <p>{@code @Configuration} を付けていないのは意図的である。付けると
     * {@code @SpringBootTest} のコンポーネントスキャン（{@code com.mannschaft.app} 配下・
     * テストクラスも classpath に載る）に拾われ、全 IT に無関係な EntityManagerFactory を
     * 撒いてしまう。{@code @EnableJpaRepositories} は {@code @Import} メタアノテーションであり、
     * lite モードの {@code @Bean} でも問題なく機能する。</p>
     */
    @EnableJpaRepositories(basePackageClasses = UnsignedIdProbeRepository.class)
    static class ProbeJpaConfig {

        @Bean
        DataSource probeDataSource() {
            DriverManagerDataSource ds = new DriverManagerDataSource(
                    MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
            ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
            return ds;
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource probeDataSource) {
            LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
            emf.setDataSource(probeDataSource);
            emf.setPackagesToScan(UnsignedIdProbeEntity.class.getPackage().getName());
            emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            Properties props = new Properties();
            // Flyway が作った実スキーマをそのまま使う。Entity から DDL を生成させない。
            props.put(AvailableSettings.HBM2DDL_AUTO, "none");
            props.put(AvailableSettings.DIALECT, MySQLDialect.class.getName());
            emf.setJpaProperties(props);
            return emf;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
            return new JpaTransactionManager(entityManagerFactory);
        }
    }

    /** {@code information_schema} から列の実型（{@code COLUMN_TYPE}）を読む。 */
    private static String readColumnType(String table, String column) throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "SELECT COLUMN_TYPE FROM information_schema.columns "
                             + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as(table + "." + column + " が実スキーマに存在すること").isTrue();
                return rs.getString(1).toLowerCase(Locale.ROOT);
            }
        }
    }

    /**
     * 指定テーブルに実測用の 1 行を用意する（既に行があれば何もしない）。
     *
     * <p>NOT NULL かつデフォルト無し・非 AUTO_INCREMENT・非生成列を
     * {@code information_schema} から拾い、型に応じたダミー値で充填する。
     * 列構成の変化に追随させるため、固定の INSERT 文は書かない。</p>
     */
    private static void ensureProbeRow(String table) throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             java.sql.Statement st = conn.createStatement()) {

            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
                rs.next();
                if (rs.getInt(1) > 0) {
                    return;
                }
            }

            List<String> columns = new ArrayList<>();
            List<String> values = new ArrayList<>();
            try (ResultSet rs = st.executeQuery(
                    "SELECT COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, EXTRA FROM information_schema.columns "
                            + "WHERE table_schema = DATABASE() AND table_name = '" + table + "' "
                            + "AND IS_NULLABLE = 'NO' AND COLUMN_DEFAULT IS NULL")) {
                while (rs.next()) {
                    String extra = rs.getString("EXTRA") == null
                            ? "" : rs.getString("EXTRA").toLowerCase(Locale.ROOT);
                    if (extra.contains("auto_increment") || extra.contains("generated")) {
                        continue;
                    }
                    columns.add("`" + rs.getString("COLUMN_NAME") + "`");
                    values.add(dummyLiteral(
                            rs.getString("DATA_TYPE").toLowerCase(Locale.ROOT),
                            rs.getString("COLUMN_TYPE")));
                }
            }

            st.execute("SET FOREIGN_KEY_CHECKS = 0");
            if (columns.isEmpty()) {
                st.executeUpdate("INSERT INTO " + table + " () VALUES ()");
            } else {
                st.executeUpdate("INSERT INTO " + table + " (" + String.join(", ", columns)
                        + ") VALUES (" + String.join(", ", values) + ")");
            }
            st.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    /** MySQL の DATA_TYPE に応じた最小のダミーリテラルを返す。 */
    private static String dummyLiteral(String dataType, String columnType) {
        switch (dataType) {
            case "tinyint", "smallint", "mediumint", "int", "integer", "bigint", "bit",
                 "decimal", "numeric", "float", "double", "year":
                return "1";
            case "date":
                return "'2000-01-01'";
            case "datetime", "timestamp":
                return "'2000-01-01 00:00:00'";
            case "time":
                return "'00:00:00'";
            case "json":
                return "'{}'";
            case "enum", "set": {
                // COLUMN_TYPE は enum('A','B') 形式。先頭の候補値を使う。
                int open = columnType.indexOf('\'');
                int close = columnType.indexOf('\'', open + 1);
                return columnType.substring(open, close + 1);
            }
            default:
                return "'p'";
        }
    }

    /** 本番の fresh 構築と同条件（out-of-order 無効）で全マイグレーションを適用する。冪等。 */
    private static MigrateResult migrateFromScratch() {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
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
