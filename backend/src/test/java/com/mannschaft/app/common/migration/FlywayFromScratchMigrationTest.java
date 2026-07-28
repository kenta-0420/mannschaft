package com.mannschaft.app.common.migration;

import jakarta.persistence.Converter;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.MappedSuperclass;
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
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
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
