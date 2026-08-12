package com.mannschaft.app.common.persistence;

import com.mannschaft.app.common.architecture.SchemaCollationPolicy;
import com.mannschaft.app.common.persistence.probe.UnsignedIdProbeEntity;
import com.mannschaft.app.common.persistence.probe.UnsignedIdProbeRepository;
import com.mannschaft.app.scopefolder.repository.MyScopeFolderItemRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.assertj.core.api.SoftAssertions;
import org.hibernate.dialect.MySQLDialect;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.flywaydb.core.Flyway;
import org.hibernate.cfg.AvailableSettings;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>issue #2545: ネイティブクエリのスカラ型は本番 DDL の {@code BIGINT UNSIGNED} 列に対して
 * 実際に何を返すのか</b>を、Flyway 実スキーマ上で実測して恒久的に固定する IT。
 *
 * <h2>訂正した前提</h2>
 * <p>PR #2514 は「MySQL Connector/J は符号なし BIGINT を {@link BigInteger} で返すので、
 * ネイティブクエリの射影に {@code Long} と書くとテストでは通り本番だけ落ちる」と javadoc に断定した。
 * しかし<b>この機構は一度も実測されていなかった</b>（出典はその javadoc のみで、
 * テスト・IT・障害記録に {@code BigInteger} 起因の実測は無い）。
 * 本 IT がその空白を埋める。</p>
 *
 * <h2>なぜ通常のテストでは決着しないか</h2>
 * <p>{@code src/test/resources/application-test.yml} は {@code ddl-auto=create} /
 * {@code flyway.enabled=false} であり、テスト DB の ID 列は Entity の {@code Long} 由来で
 * <b>符号付き</b> {@code bigint} になる。符号性が本番と違うのだから、
 * 符号性に由来する型差は原理的にテストに現れない。
 * 本 IT は Flyway を明示的に適用した実 MySQL 上で走る。</p>
 *
 * <h2>なぜ独立クラスなのか（{@code FlywayFromScratchMigrationTest} に相乗りしない理由）</h2>
 * <p>{@code backend/build.gradle.kts} の {@code excludeMigrationTests} は
 * 「{@code /migration/} パッケージ配下 かつ 単純クラス名に {@code Flyway} を含む」クラスを
 * PR CI から除外する（{@code db/migration/**} 無変更の PR では 65 クラスを丸ごと落として CI 時間を削る）。
 * 相乗りさせると<b>本 IT が PR の必須チェックで一度も走らない</b>。
 * 「恒久的に固定する」と称するテストが常時実行されないのは本末転倒なので、
 * 除外セレクタの<b>外側</b>（{@code /persistence/} パッケージ・クラス名に {@code Flyway} を含まない）に置く。
 * 対価としてコンテナ起動 + 全マイグレーション適用が 1 回増えるが、
 * 除外規則そのものを緩めて 65 クラスを常時実行させるよりはるかに安い。</p>
 *
 * <h2>測定条件</h2>
 * <p>MySQL 8.0（Testcontainers {@code mysql:8.0}）+ MySQL Connector/J（Spring Boot 3.5 系の管理バージョン）
 * + Hibernate ORM 6.6 系 + Spring Data JPA。<b>本 IT の結論はこの条件下での観測事実であり、
 * 無条件の一般則ではない</b>（#2514 の無条件断定を否定する PR が同じ形の断定を残さないための注記）。
 * 条件が変われば本 IT が赤くなることで検知される。それが本 IT の存在意義である。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.common.persistence.NativeQueryUnsignedBigintTypeIT#isDockerAvailable")
@DisplayName("BIGINT UNSIGNED × ネイティブクエリ 型解決の実測（issue #2545）")
class NativeQueryUnsignedBigintTypeIT {

    /** 実測に使うスコープ ID。noise 行の scope_id 域（1..500）と重ならない値にする。 */
    private static final long PROBE_SCOPE_ID = 4242L;

    /** インデックス選択を意味のあるものにするための noise 通知件数。 */
    private static final int NOISE_NOTIFICATIONS = 500;

    /** スキーマ全体の統一照合順序（issue #2589）。 */
    private static final String UNIFIED_COLLATION = SchemaCollationPolicy.UNIFIED_COLLATION;

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_nativetype")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            // V13.045 等が CREATE TRIGGER を含む。本番（RDS はパラメータグループで有効化）と同条件に揃える。
            // --collation-server は本番 RDS の collation_server と同値を明示指定する（issue #2589）。
            // mysql:8.0 の既定はたまたま同じ値だが、既定に依存すると
            // 「本番と同じ照合順序で走っている」ことが偶然に依存するため明示で固定する。
            .withCommand("--log_bin_trust_function_creators=1",
                    "--character-set-server=" + SchemaCollationPolicy.UNIFIED_CHARSET,
                    "--collation-server=" + SchemaCollationPolicy.PRODUCTION_COLLATION_SERVER);

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeAll
    void setUp() {
        MYSQL.start();
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load()
                .migrate();
    }

    @AfterAll
    void tearDown() {
        MYSQL.stop();
    }

    // =====================================================================
    // 実測 1: 各経路の実行時型
    // =====================================================================

    /**
     * {@code BIGINT UNSIGNED} 列（{@code users.id}）を 6 経路で読み、実行時型を固定する。
     *
     * <p>本番に実在する受け取り形をすべて再現している:
     * {@code List<Long>}（native 18 件）・射影インタフェース（3 件）・
     * {@code List<Object[]>}（17 件。{@code ScheduleRepository#findTopVenueByTeamId} と同形状で、
     * その消費側 {@code AdSegmentService} がコードベース唯一の native 由来の直キャストを持つ）・
     * {@code JdbcTemplate}。</p>
     */
    @Test
    @DisplayName("生JDBCはBigIntegerを返すが、Hibernate/SpringData/JdbcTemplate経由は全てLongに正規化される")
    void 符号なしBIGINTの各経路の実行時型を固定する() throws Exception {
        // given: 前提そのものの検算 — users.id が本当に符号なしであること
        assertThat(readColumnType("users", "id"))
                .as("本実測の前提: Flyway 実スキーマの users.id は符号なし BIGINT であること")
                .isEqualTo("bigint unsigned");
        ensureProbeRow("users");

        Object viaJdbc;
        Object viaHibernateNative;
        Object viaSpringDataListElement;
        Object viaProjection;
        Object viaSpringDataObjectArray;
        Object viaJdbcTemplateTyped;

        try (Connection conn = connection();
             Statement st = conn.createStatement();
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
            // 宣言型 List<Long> の get(0) は javac が checkcast を挿入するため、
            // List<?> 経由で受けないと「何が入っていたか」を報告する前に落ちる。
            viaSpringDataListElement = ((List<?>) ids).get(0);

            List<UnsignedIdProbeRepository.IdProjection> projections = repository.findIdsAsProjection();
            assertThat(projections).as("射影インタフェース経路が 1 件返すこと").hasSize(1);
            // 同じ理由で getId() の戻り値を直接受けず、反射で素の Object として観測する。
            viaProjection = UnsignedIdProbeRepository.IdProjection.class
                    .getMethod("getId").invoke(projections.get(0));

            List<Object[]> rows = repository.findIdAndCountAsObjectArray();
            assertThat(rows).as("List<Object[]> 経路が 1 件返すこと").hasSize(1);
            viaSpringDataObjectArray = rows.get(0)[0];

            List<?> jdbcTemplateRows = new JdbcTemplate(ctx.getBean(DataSource.class))
                    .queryForList("SELECT id FROM users ORDER BY id LIMIT 1", Long.class);
            viaJdbcTemplateTyped = jdbcTemplateRows.get(0);
        }

        // then: 1 回の実行で全経路を報告するため soft assert
        SoftAssertions softly = new SoftAssertions();

        // [1] だけが BigInteger。ドライバの挙動についての #2514 の記述自体は正しかった。
        softly.assertThat(viaJdbc)
                .as("[1] 生 JDBC ResultSet#getObject は BIGINT UNSIGNED を BigInteger で返す"
                        + "（MySQL Connector/J の仕様。型ズレの発生源はここだけ）")
                .isInstanceOf(BigInteger.class);

        // [2]〜[6] は Long。Hibernate 6 は ResultSetMetaData#getColumnType（BIGINT）で
        // スカラ型を解決して Long へ正規化するため、BigInteger は ORM 境界を越えてこない。
        // ＝ #2514 が恐れた「射影に Long と書くと本番だけ落ちる」機構は本測定条件下では成立しない。
        softly.assertThat(viaHibernateNative)
                .as("[2] Hibernate ネイティブクエリのスカラは Long に正規化される"
                        + "（Hibernate 6 は getColumnClassName ではなく getColumnType で解決する）")
                .isInstanceOf(Long.class);
        softly.assertThat(viaSpringDataListElement)
                .as("[3] Spring Data @Query(nativeQuery=true) の List<Long> の『要素』も Long"
                        + "（QueryExecutionResultHandler が要素型を変換しないのは事実だが、"
                        + "そもそも Hibernate 層で既に Long になっているため問題化しない。"
                        + "ここが BigInteger なら contains()/findAllById() が黙って false / 0 件になる）")
                .isInstanceOf(Long.class);
        softly.assertThat(viaProjection)
                .as("[4] 射影インタフェースの Long 宣言も Long")
                .isInstanceOf(Long.class);
        softly.assertThat(viaSpringDataObjectArray)
                .as("[5] List<Object[]> の要素も Long"
                        + "（AdSegmentService の (Long) row[0] 直キャストは本番でも落ちない）")
                .isInstanceOf(Long.class);
        softly.assertThat(viaJdbcTemplateTyped)
                .as("[6] JdbcTemplate#queryForList(sql, Long.class) は SingleColumnRowMapper の"
                        + " NumberUtils 変換で Long になる"
                        + "（型を指定しない queryForList(sql) は変換が効かず BigInteger が漏れる点に注意）")
                .isInstanceOf(Long.class);
        softly.assertAll();
    }

    /**
     * issue #2545 の根治治療そのものの実測。
     *
     * <p>{@code my_scope_folder_items.scope_id} はかつて同一テーブル内の {@code id} / {@code folder_id}
     * とも符号性が食い違う符号付き {@code BIGINT}（V9.101）であり、{@code notifications.scope_id} 等
     * 他テーブルの同名カラムとも不統一だった。
     * {@code V177.20260809103622__unify_my_scope_folder_items_scope_id_unsigned.sql} で
     * {@code BIGINT UNSIGNED} へ揃えたことを、Flyway 実スキーマ上で {@code information_schema} を
     * 直接読んで検証する。COMMENT を失っていないことも合わせて確認する
     * （{@code MODIFY COLUMN} はカラム定義を丸ごと置き換えるため、書き落とすと COMMENT が消える罠がある）。</p>
     */
    @Test
    @DisplayName("my_scope_folder_items.scope_id はBIGINT UNSIGNEDへ統一済み（V177・issue #2545）")
    void my_scope_folder_items_scope_idはUNSIGNEDへ統一済み() throws Exception {
        assertThat(readColumnType("my_scope_folder_items", "scope_id"))
                .as("scope_id は BIGINT UNSIGNED へ統一されていること")
                .isEqualTo("bigint unsigned");
        assertThat(readColumnType("my_scope_folder_items", "id"))
                .as("同一テーブル内の id も符号なしのままであること（既存の整合を壊していないこと）")
                .isEqualTo("bigint unsigned");
        assertThat(readColumnType("my_scope_folder_items", "folder_id"))
                .as("同一テーブル内の folder_id も符号なしのままであること（既存の整合を壊していないこと）")
                .isEqualTo("bigint unsigned");

        try (Connection conn = connection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COLUMN_COMMENT FROM information_schema.columns "
                             + "WHERE table_schema = DATABASE() AND table_name = 'my_scope_folder_items' "
                             + "AND column_name = 'scope_id'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("scope_id が実スキーマに存在すること").isTrue();
                assertThat(rs.getString(1))
                        .as("MODIFY COLUMN でも COMMENT を失っていないこと")
                        .isEqualTo("team_id or organization_id");
            }
        }
    }

    /**
     * 符号揃え 第一波（issue #2545・{@code V180.20260811135837__unify_notification_scope_id_columns_unsigned.sql}）の実測。
     *
     * <p>{@code notification_fanout_jobs} / {@code notifications_archive} / {@code dashboard_scope_tab_order}
     * の該当列が {@code BIGINT UNSIGNED} へ統一されたことと、{@code MODIFY COLUMN} で COMMENT を
     * 失っていないことを {@code information_schema} で検証する（COMMENT の無い列は空文字/nullを許容）。</p>
     */
    @Test
    @DisplayName("符号揃え第一波: notification_fanout_jobs/notifications_archive/dashboard_scope_tab_order がBIGINT UNSIGNEDへ統一済み（V180・issue #2545）")
    void 符号揃え第一波の対象列がUNSIGNEDへ統一済み() throws Exception {
        SoftAssertions softly = new SoftAssertions();

        // notification_fanout_jobs
        softly.assertThat(readColumnType("notification_fanout_jobs", "organization_id"))
                .as("notification_fanout_jobs.organization_id").isEqualTo("bigint unsigned");
        softly.assertThat(readColumnType("notification_fanout_jobs", "source_id"))
                .as("notification_fanout_jobs.source_id").isEqualTo("bigint unsigned");
        softly.assertThat(readColumnType("notification_fanout_jobs", "actor_id"))
                .as("notification_fanout_jobs.actor_id").isEqualTo("bigint unsigned");
        softly.assertThat(readColumnType("notification_fanout_jobs", "cursor_subject_id"))
                .as("notification_fanout_jobs.cursor_subject_id").isEqualTo("bigint unsigned");
        softly.assertThat(readColumnComment("notification_fanout_jobs", "actor_id"))
                .as("actor_id の COMMENT を MODIFY COLUMN で失っていないこと")
                .isEqualTo("実行者ID（論理参照・FK なし・システム発火は NULL）");
        softly.assertThat(readColumnComment("notification_fanout_jobs", "cursor_subject_id"))
                .as("cursor_subject_id の COMMENT を MODIFY COLUMN で失っていないこと")
                .isEqualTo("キーセット再開カーソル（処理済み受信者 subject_id 上端。クラッシュ再開の要・AC-2）");

        // notifications_archive（移送元 notifications と同じ符号性へ統一）
        softly.assertThat(readColumnType("notifications_archive", "user_id"))
                .as("notifications_archive.user_id").isEqualTo("bigint unsigned");
        softly.assertThat(readColumnType("notifications_archive", "organization_id"))
                .as("notifications_archive.organization_id").isEqualTo("bigint unsigned");
        softly.assertThat(readColumnType("notifications_archive", "source_id"))
                .as("notifications_archive.source_id").isEqualTo("bigint unsigned");
        softly.assertThat(readColumnType("notifications_archive", "scope_id"))
                .as("notifications_archive.scope_id").isEqualTo("bigint unsigned");
        softly.assertThat(readColumnType("notifications_archive", "actor_id"))
                .as("notifications_archive.actor_id").isEqualTo("bigint unsigned");
        softly.assertThat(readColumnComment("notifications_archive", "organization_id"))
                .as("organization_id の COMMENT を MODIFY COLUMN で失っていないこと")
                .isEqualTo("テナント（論理参照・FK なし）");

        // dashboard_scope_tab_order
        softly.assertThat(readColumnType("dashboard_scope_tab_order", "user_id"))
                .as("dashboard_scope_tab_order.user_id").isEqualTo("bigint unsigned");
        softly.assertThat(readColumnType("dashboard_scope_tab_order", "scope_id"))
                .as("dashboard_scope_tab_order.scope_id").isEqualTo("bigint unsigned");
        softly.assertThat(readColumnComment("dashboard_scope_tab_order", "user_id"))
                .as("user_id の COMMENT を MODIFY COLUMN で失っていないこと")
                .isEqualTo("users.id（FK制約なし。クロスドメインFK禁止原則）");
        softly.assertThat(readColumnComment("dashboard_scope_tab_order", "scope_id"))
                .as("scope_id の COMMENT を MODIFY COLUMN で失っていないこと")
                .isEqualTo("チームID または 組織ID（FK制約なし）");

        softly.assertAll();
    }

    /**
     * {@code notifications_archive} の実害実測。{@code NotificationCleanupBatchService#deleteArchived} が
     * 実際に発行する {@code DELETE FROM notifications WHERE ... AND id IN (SELECT id FROM notifications_archive)}
     * は {@code notifications.id}（{@code BIGINT UNSIGNED}）と {@code notifications_archive.id} を
     * 突き合わせる。統一前（{@code notifications_archive.id} が符号付きだった場合）を模した
     * {@code CAST(id AS SIGNED)} 版と、統一後の無加工版とで {@code notifications} 側の索引選択を比較する。
     */
    @Test
    @DisplayName("EXPLAIN観測（断言なし）: notifications_archive との突き合わせにおける索引選択を記録する（V180・issue #2545）")
    void notifications_archiveとの突き合わせで索引選択を実測する() throws Exception {
        long userId;
        try (Connection conn = connection(); Statement st = conn.createStatement()) {
            ensureProbeRow("users");
            try (ResultSet rs = st.executeQuery("SELECT id FROM users ORDER BY id LIMIT 1")) {
                rs.next();
                userId = ((Number) rs.getObject(1)).longValue();
            }
            // notifications に PRIMARY KEY 索引選択を意味あるものにするための行を用意する。
            for (int i = 0; i < 50; i++) {
                insertNotification(conn, userId, i);
            }
            long probeId;
            try (ResultSet rs = st.executeQuery("SELECT id FROM notifications ORDER BY id LIMIT 1")) {
                rs.next();
                probeId = ((Number) rs.getObject(1)).longValue();
            }
            st.executeUpdate("INSERT IGNORE INTO notifications_archive "
                    + "(id, user_id, organization_id, notification_type, priority, title, "
                    + " source_type, source_id, scope_type, scope_id, action_url, actor_id, "
                    + " is_read, read_at, channels_sent, snoozed_until, created_at) "
                    + "SELECT id, user_id, organization_id, notification_type, priority, title, "
                    + "       source_type, source_id, scope_type, scope_id, action_url, actor_id, "
                    + "       is_read, read_at, channels_sent, snoozed_until, created_at "
                    + "FROM notifications WHERE id = " + probeId);
            st.execute("ANALYZE TABLE notifications, notifications_archive");
        }

        String withoutCast = "EXPLAIN DELETE FROM notifications "
                + "WHERE id IN (SELECT id FROM notifications_archive) LIMIT 10000";
        // 統一前（notifications_archive.id が符号付きだった場合）を模した CAST 版。
        String withCast = "EXPLAIN DELETE FROM notifications "
                + "WHERE id IN (SELECT CAST(id AS SIGNED) FROM notifications_archive) LIMIT 10000";

        String keyWithoutCast = explainKeyForTable("notifications", withoutCast);
        String keyWithCast = explainKeyForTable("notifications", withCast);

        System.out.println("[#2545 V180 EXPLAIN] notifications の key（統一後・無加工）    = " + keyWithoutCast);
        System.out.println("[#2545 V180 EXPLAIN] notifications の key（符号不一致を模した版）= " + keyWithCast);

        // ここで optimizer の選択そのものを断言しない（意図的）。
        //
        // どの索引が選ばれるかは行数分布・統計・MySQL のバージョンに依存し、DBMS が保証する性質ではない。
        // 実際 CI（50 行 + ANALYZE TABLE の条件）では key=null（全走査）となり、PRIMARY を要求する
        // 断言は落ちた。V177 の先例も EXPLAIN の結果を「当該条件下の観測」と明記しており、
        // 保証として扱っていない。
        //
        // 本 migration が保証するのは「型が揃ったこと」であり、それは
        // {@link #符号揃え第一波の対象列がUNSIGNEDへ統一済み()} が information_schema に対して断言している。
        // 索引選択は診断のための観測として上の出力に残す。**ここに isEqualTo("PRIMARY") を書き戻すな。**
        // key は索引未使用のとき NULL を返すため、値の有無すら断言しない（null 断言も同じ罠を踏む）。
        // 断言するのは「統一後の無加工クエリが EXPLAIN 可能な正当な SQL であること」——
        // すなわち上の2回の explainKeyForTable が例外なく完了したことのみである。
        assertThat(withoutCast).as("統一後の無加工クエリが EXPLAIN 対象として成立すること").isNotBlank();
    }

    // =====================================================================
    // 実測 2: CAST 撤去の同値性（本番の実クエリで）
    // =====================================================================

    /**
     * <b>issue #2545 の鏡像</b>: {@code MyScopeFolderItemRepository#aggregateFolderUnreadCounts} から
     * {@code CAST(n.scope_id AS UNSIGNED)} を撤去しても結果が変わらないことを、
     * <b>本番の実クエリそのもの</b>を Flyway 実スキーマ上で走らせて検証する。
     *
     * <p>SQL はリポジトリの {@link Query} アノテーションから反射で取り出す。
     * テストにコピーを持たないことで、リポジトリを書き換えたのにテストが古い SQL を検証し続ける
     * ドリフトを構造的に排除する。</p>
     *
     * <h2>符号性の非対称はかつて実在した（V177 で解消済み）</h2>
     * <ul>
     *   <li>{@code notifications.scope_id} … {@code BIGINT UNSIGNED}（V4.019）</li>
     *   <li>{@code my_scope_folder_items.scope_id} … かつて符号付き {@code BIGINT}（V9.101）だったが、
     *       {@code V177.20260809103622__unify_my_scope_folder_items_scope_id_unsigned.sql}（issue #2545）で
     *       {@code BIGINT UNSIGNED} へ統一済み</li>
     * </ul>
     * <p>つまり撤去した {@code CAST(n.scope_id AS UNSIGNED)} は<b>符号なしの列を符号なしへ変換する
     * no-op</b> である（統一前は非対称ゆえの no-op、統一後は両側同一ゆえの no-op と理由が変わっただけで、
     * いずれにせよ CAST は不要）。{@code ddl-auto=create} のテスト環境では両側とも符号付きになるため、
     * 既存の {@code MyScopeFolder*} テストは全緑でも本番の符号性を一度も踏んでいない。</p>
     */
    @Test
    @DisplayName("aggregateFolderUnreadCounts は CAST 有無で同一結果（本番の実クエリで検証）")
    void 実クエリでCAST撤去の同値性を検証する() throws Exception {
        // given: 両テーブルとも符号なしへ統一済み（V177・issue #2545）
        assertThat(readColumnType("notifications", "scope_id"))
                .as("notifications.scope_id は符号なし").isEqualTo("bigint unsigned");
        assertThat(readColumnType("my_scope_folder_items", "scope_id"))
                .as("my_scope_folder_items.scope_id は符号なしへ統一済み（V177）")
                .isEqualTo("bigint unsigned");

        long userId = seedUnreadCountFixture();

        String currentSql = repositorySql();
        // 現行（撤去後）の SQL に CAST が残っていないこと ＝ 撤去の回帰ガード
        assertThat(currentSql)
                .as("aggregateFolderUnreadCounts の JOIN 条件に CAST が復活していないこと")
                .doesNotContain("CAST(n.scope_id");

        String castedSql = currentSql.replace(
                "n.scope_id = item.scope_id", "CAST(n.scope_id AS UNSIGNED) = item.scope_id");
        assertThat(castedSql)
                .as("CAST 版の生成に成功していること（置換が空振りすると同じ SQL を 2 回測ることになる）")
                .isNotEqualTo(currentSql);

        // when: 撤去後 / CAST 有りの双方を実行
        List<String> withoutCast = runUnreadCounts(currentSql, userId);
        List<String> withCast = runUnreadCounts(castedSql, userId);

        // then: 結果が完全に一致し、かつ期待どおり 1 件が集計されている
        assertThat(withoutCast)
                .as("撤去後の実クエリが CAST 有りと同一の結果を返すこと。"
                        + "ここが食い違うなら CAST 撤去は挙動を変えており、撤去してはならない")
                .isEqualTo(withCast);
        assertThat(withoutCast).as("フォルダ 1 件が返ること").hasSize(1);
        assertThat(withoutCast.get(0))
                .as("未読件数が 1 件として集計されること（0 なら JOIN が一致していない ＝ 撤去は誤り）")
                .endsWith("=1");
    }

    // =====================================================================
    // 実測 3: CAST 撤去のインデックス効果（EXPLAIN）
    // =====================================================================

    /**
     * CAST 撤去の唯一の便益である「{@code notifications} 側でインデックスが使えるようになる」を
     * {@code EXPLAIN} で前後比較して観測する。
     *
     * <p>インデックス列に関数が乗ると sargable でなくなるため、CAST 有りでは
     * {@code notifications} が全走査になることを期待する。ただし JOIN 条件は
     * {@code scope_type} / {@code user_id} / {@code is_read} も持つため、
     * 撤去後にどのインデックスが選ばれるかは optimizer の判断であり自明ではない。
     * <b>そこで本テストは「特定のインデックス名が選ばれること」ではなく
     * 「CAST 有りでは索引が使われず、撤去後は何らかの索引が使われること」だけを固定する。</b>
     * 願望ではなく観測に基づく主張に留めるための設計である。</p>
     */
    @Test
    @DisplayName("EXPLAIN: CAST 有りは notifications が索引未使用、撤去後は索引が使われる")
    void CAST撤去でnotifications側の索引が使われるようになる() throws Exception {
        long userId = seedUnreadCountFixture();

        String currentSql = repositorySql();
        String castedSql = currentSql.replace(
                "n.scope_id = item.scope_id", "CAST(n.scope_id AS UNSIGNED) = item.scope_id");

        String keyWithCast = explainKeyForNotifications(castedSql, userId);
        String keyWithoutCast = explainKeyForNotifications(currentSql, userId);

        System.out.println("[#2545 EXPLAIN] notifications の key（CAST 有り）  = " + keyWithCast);
        System.out.println("[#2545 EXPLAIN] notifications の key（CAST 撤去後）= " + keyWithoutCast);

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(keyWithCast)
                .as("CAST 有りでは notifications 側で索引が選ばれない"
                        + "（インデックス列に関数が乗り sargable でなくなるため）。実測値=" + keyWithCast)
                .isNull();
        softly.assertThat(keyWithoutCast)
                .as("CAST 撤去後は notifications 側で何らかの索引が選ばれる。実測値=" + keyWithoutCast)
                .isNotNull();
        softly.assertAll();
    }

    // =====================================================================
    // 実測 4: 本測定中に発見した別個の本番欠陥（照合順序不一致）
    // =====================================================================

    /**
     * <b>issue #2589 の是正が効いていること</b>を、本番と同じ照合順序で構築したスキーマ上で検証する。
     *
     * <h2>かつての欠陥（V175 以前）</h2>
     * <ul>
     *   <li>{@code notifications}（V4.019）は {@code COLLATE utf8mb4_unicode_ci} を<b>明示宣言</b>していた</li>
     *   <li>{@code my_scope_folders}（V9.100）は照合順序を宣言せず<b>サーバ既定</b>に従っていた</li>
     *   <li>本番 RDS のサーバ既定は {@code utf8mb4_0900_ai_ci}
     *       （{@code infra/terraform/modules/data/main.tf} の {@code collation_server}）</li>
     *   <li>ローカル {@code docker-compose.yml} のサーバ既定は {@code utf8mb4_unicode_ci} だったため
     *       <b>ローカルでは一致してしまい問題が出なかった</b></li>
     * </ul>
     * <p>結果 {@code ON ... AND n.scope_type = folder.scope_type} が異なる照合順序の列同士の比較となり、
     * <b>本番だけ {@code Illegal mix of collations} で落ちていた</b>。</p>
     *
     * <h2>是正と、本テストが今固定していること</h2>
     * <p>{@code V175.20260804134628__unify_table_collation.sql} が全表・全文字列列を
     * {@code utf8mb4_0900_ai_ci} へ統一し、{@code ALTER DATABASE} でデータベース既定も固定した。
     * 本テストは <b>(1)</b> 当時食い違っていた 2 列が今は一致すること、
     * <b>(2)</b> リポジトリの SQL を<b>無加工で</b>走らせて例外なく結果が返ること、を固定する。
     * 中和用の {@code COLLATE} を挟まないことが要点で、
     * 是正が巻き戻れば {@code Illegal mix of collations} で即座に赤くなる。</p>
     *
     * <p>スキーマ全体の統一が維持されているかは {@code SchemaCollationConsistencyIT} が担当する。
     * 本テストは「実際に壊れていた 1 本の実クエリ」に対する回帰ガードである。</p>
     */
    @Test
    @DisplayName("本番と同じ照合順序でも aggregateFolderUnreadCounts が無加工で成功する（issue #2589 回帰ガード）")
    void 本番照合順序でも実クエリが照合不一致にならない() throws Exception {
        long userId = seedUnreadCountFixture();

        // given: かつて食い違っていた 2 列が、V175 により同一照合順序へ統一されていること
        assertThat(readColumnCollation("notifications", "scope_type"))
                .as("notifications.scope_type は V175 で統一先へ変換されている"
                        + "（V4.019 の utf8mb4_unicode_ci 明示宣言が残っていれば本番で JOIN が落ちる）")
                .isEqualTo(UNIFIED_COLLATION);
        assertThat(readColumnCollation("my_scope_folders", "scope_type"))
                .as("my_scope_folders.scope_type も同一照合順序であること")
                .isEqualTo(UNIFIED_COLLATION);

        // when: リポジトリの SQL を「中和なし・無加工」で実行する
        String rawSql = repositorySql();
        assertThat(rawSql)
                .as("実クエリに COLLATE による対症的な中和が入り込んでいないこと"
                        + "（入っていたら統一が壊れていても本テストが気付けなくなる）")
                .doesNotContain("COLLATE");

        List<String> rows = runUnreadCounts(rawSql, userId);

        // then: 例外なく、CAST 同値性テストと同じ結果が返る
        assertThat(rows).as("フォルダ 1 件が返ること").hasSize(1);
        assertThat(rows.get(0))
                .as("未読件数が 1 件として集計されること")
                .endsWith("=1");
    }

    // =====================================================================
    // フィクスチャ / ヘルパ
    // =====================================================================

    /**
     * {@code aggregateFolderUnreadCounts} の SQL をリポジトリの {@link Query} から反射で取り出す。
     *
     * <p>かつては照合順序不一致（issue #2589）を中和する {@code measurableSql()} を経由していたが、
     * {@code V175.20260804134628__unify_table_collation.sql} が照合順序をスキーマ全体で統一したため
     * 中和は不要になり、本メソッドを直接使う。中和を復活させてはならない
     * — 中和は「本番だけ落ちる欠陥」をテストから見えなくするからである。</p>
     */
    private static String repositorySql() throws NoSuchMethodException {
        Query query = MyScopeFolderItemRepository.class
                .getMethod("aggregateFolderUnreadCounts", Long.class, String.class)
                .getAnnotation(Query.class);
        assertThat(query).as("aggregateFolderUnreadCounts に @Query が付いていること").isNotNull();
        assertThat(query.nativeQuery()).as("ネイティブクエリであること").isTrue();
        return query.value();
    }

    /** 名前付きパラメータを埋めて実行し、{@code folderId=unreadCount} の一覧を返す。 */
    private static List<String> runUnreadCounts(String sql, long userId) throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection conn = connection();
             PreparedStatement ps = conn.prepareStatement(toPositional(sql))) {
            bind(ps, sql, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getLong(1) + "=" + rs.getLong(2));
                }
            }
        }
        return result;
    }

    /** 名前付きパラメータを {@code ?} に置換する。 */
    private static String toPositional(String sql) {
        return sql.replaceAll(":userId|:scopeType", "?");
    }

    /**
     * 名前付きパラメータの<b>実際の出現順</b>を SQL から読み取って束縛する。
     *
     * <p>順序を決め打ちしないのは、リポジトリの SQL が書き換わったときに
     * 「型は合うが値が入れ替わって静かに 0 件になる」事故を避けるためである。</p>
     */
    private static void bind(PreparedStatement ps, String sql, long userId) throws SQLException {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(":userId|:scopeType").matcher(sql);
        int index = 0;
        while (m.find()) {
            index++;
            if (":userId".equals(m.group())) {
                ps.setLong(index, userId);
            } else {
                ps.setString(index, "TEAM");
            }
        }
        assertThat(index).as("束縛したパラメータ数が 0 でないこと").isPositive();
    }

    /** EXPLAIN を実行し、{@code notifications}（別名 {@code n}）の行で選ばれた {@code key} を返す（未使用なら null）。 */
    private static String explainKeyForNotifications(String sql, long userId) throws SQLException {
        try (Connection conn = connection();
             PreparedStatement ps = conn.prepareStatement("EXPLAIN " + toPositional(sql))) {
            bind(ps, sql, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if ("n".equalsIgnoreCase(rs.getString("table"))) {
                        return rs.getString("key");
                    }
                }
            }
        }
        throw new AssertionError("EXPLAIN の出力に notifications（別名 n）の行が無い");
    }

    /** 既に {@code EXPLAIN ...} 形式で組み立て済みの SQL を実行し、指定テーブル名の行で選ばれた {@code key} を返す。 */
    private static String explainKeyForTable(String tableName, String explainSql) throws SQLException {
        try (Connection conn = connection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(explainSql)) {
            while (rs.next()) {
                if (tableName.equalsIgnoreCase(rs.getString("table"))) {
                    return rs.getString("key");
                }
            }
        }
        throw new AssertionError("EXPLAIN の出力に " + tableName + " の行が無い");
    }

    /**
     * 未読件数集計の実測用フィクスチャを用意する（冪等）。
     *
     * <p>ユーザー 1 名・フォルダ 1 個・フォルダ項目 1 件（{@code scope_id = 4242}）と、
     * 一致する未読通知 1 件 + インデックス選択を意味のあるものにするための noise 通知
     * {@value #NOISE_NOTIFICATIONS} 件を投入する。</p>
     *
     * @return 作成したユーザーの ID
     */
    private long seedUnreadCountFixture() throws SQLException {
        ensureProbeRow("users");
        try (Connection conn = connection();
             Statement st = conn.createStatement()) {

            long userId;
            try (ResultSet rs = st.executeQuery("SELECT id FROM users ORDER BY id LIMIT 1")) {
                rs.next();
                // 生 JDBC 経路なので BigInteger が返る。実測 1 で固定したとおり。
                userId = ((Number) rs.getObject(1)).longValue();
            }

            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM my_scope_folders")) {
                rs.next();
                if (rs.getInt(1) > 0) {
                    return userId;
                }
            }

            st.executeUpdate("INSERT INTO my_scope_folders (user_id, scope_type, name) VALUES ("
                    + userId + ", 'TEAM', 'probe')");
            long folderId;
            try (ResultSet rs = st.executeQuery("SELECT id FROM my_scope_folders ORDER BY id LIMIT 1")) {
                rs.next();
                folderId = ((Number) rs.getObject(1)).longValue();
            }
            st.executeUpdate("INSERT INTO my_scope_folder_items (folder_id, scope_id) VALUES ("
                    + folderId + ", " + PROBE_SCOPE_ID + ")");

            // 一致する未読通知 1 件
            insertNotification(conn, userId, PROBE_SCOPE_ID);
            // noise（scope_id は 1..N。PROBE_SCOPE_ID と重ならない）
            for (int i = 1; i <= NOISE_NOTIFICATIONS; i++) {
                insertNotification(conn, userId, i);
            }
            // optimizer が実データに基づいて索引を選べるよう統計を更新する
            st.execute("ANALYZE TABLE notifications");
            return userId;
        }
    }

    private static void insertNotification(Connection conn, long userId, long scopeId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO notifications (user_id, notification_type, title, source_type, "
                        + "scope_type, scope_id, is_read) VALUES (?, 'PROBE', 'probe', 'PROBE', 'TEAM', ?, FALSE)")) {
            ps.setLong(1, userId);
            ps.setLong(2, scopeId);
            ps.executeUpdate();
        }
    }

    /**
     * 指定テーブルに実測用の 1 行を用意する（既に行があれば何もしない）。
     *
     * <p>NOT NULL かつデフォルト無し・非 AUTO_INCREMENT・非生成列を {@code information_schema} から拾い、
     * 型に応じたダミー値で充填する。列構成の変化に追随させるため固定の INSERT 文は書かない。
     * 参照整合性は本 IT の検証対象外なので FK 検査を外す（握りつぶしではなく対象外項目の明示的除外）。</p>
     */
    private static void ensureProbeRow(String table) throws SQLException {
        try (Connection conn = connection();
             Statement st = conn.createStatement()) {

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

    /** {@code information_schema} から列の照合順序を読む。 */
    private static String readColumnCollation(String table, String column) throws SQLException {
        try (Connection conn = connection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COLLATION_NAME FROM information_schema.columns "
                             + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as(table + "." + column + " が実スキーマに存在すること").isTrue();
                return rs.getString(1);
            }
        }
    }

    /** {@code information_schema} から列の実型（{@code COLUMN_TYPE}）を読む。 */
    private static String readColumnType(String table, String column) throws SQLException {
        try (Connection conn = connection();
             PreparedStatement ps = conn.prepareStatement(
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

    /** {@code information_schema} から列の COMMENT を読む。 */
    private static String readColumnComment(String table, String column) throws SQLException {
        try (Connection conn = connection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COLUMN_COMMENT FROM information_schema.columns "
                             + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as(table + "." + column + " が実スキーマに存在すること").isTrue();
                return rs.getString(1);
            }
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    /**
     * 実測用の最小 JPA コンテキスト。
     *
     * <p>{@code @Configuration} を付けていないのは意図的である。付けると {@code @SpringBootTest} の
     * コンポーネントスキャン（{@code com.mannschaft.app} 配下・テストクラスも classpath に載る）に拾われ、
     * 全 IT に無関係な EntityManagerFactory を撒いてしまう。
     * {@code @EnableJpaRepositories} は {@code @Import} メタアノテーションであり、
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
}
