package com.mannschaft.app.common.persistence;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_nativetype")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            // V13.045 等が CREATE TRIGGER を含む。本番（RDS はパラメータグループで有効化）と同条件に揃える。
            .withCommand("--log_bin_trust_function_creators=1");

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
     * <h2>符号性の非対称は実在する</h2>
     * <ul>
     *   <li>{@code notifications.scope_id} … {@code BIGINT UNSIGNED}（V4.019）</li>
     *   <li>{@code my_scope_folder_items.scope_id} … 符号付き {@code BIGINT}（V9.101）</li>
     * </ul>
     * <p>つまり撤去した {@code CAST(n.scope_id AS UNSIGNED)} は<b>既に符号なしの列を符号なしへ変換する
     * no-op</b> だった。{@code ddl-auto=create} のテスト環境では両側とも符号付きになるため、
     * 既存の {@code MyScopeFolder*} テストは全緑でも本番の符号性差を一度も踏んでいない。</p>
     */
    @Test
    @DisplayName("aggregateFolderUnreadCounts は CAST 有無で同一結果（本番の実クエリで検証）")
    void 実クエリでCAST撤去の同値性を検証する() throws Exception {
        // given: 前提となる符号性の非対称
        assertThat(readColumnType("notifications", "scope_id"))
                .as("notifications.scope_id は符号なし").isEqualTo("bigint unsigned");
        assertThat(readColumnType("my_scope_folder_items", "scope_id"))
                .as("my_scope_folder_items.scope_id は符号付き").isEqualTo("bigint");

        long userId = seedUnreadCountFixture();

        String currentSql = measurableSql();
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

        String currentSql = measurableSql();
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
     * <b>【新規発見・本 issue の想定外】{@code aggregateFolderUnreadCounts} は本番 RDS の
     * 照合順序設定では {@code Illegal mix of collations} で失敗する。</b>
     *
     * <h2>機構</h2>
     * <ul>
     *   <li>{@code notifications}（V4.019）は {@code COLLATE utf8mb4_unicode_ci} を<b>明示宣言</b>している</li>
     *   <li>{@code my_scope_folders}（V9.100）は照合順序を宣言せず<b>サーバ既定</b>に従う</li>
     *   <li>本番 RDS のサーバ既定は {@code utf8mb4_0900_ai_ci}
     *       （{@code infra/terraform/modules/data/main.tf} の {@code collation_server}。
     *       「MySQL 8.0 標準の ICU ベース照合順序」として意図的に選択されている）</li>
     *   <li>ローカル {@code docker-compose.yml} のサーバ既定は {@code utf8mb4_unicode_ci}
     *       （{@code --collation-server}）なので<b>ローカルでは一致してしまい問題が出ない</b></li>
     * </ul>
     *
     * <p>結果、{@code ON ... AND n.scope_type = folder.scope_type} が異なる照合順序の列同士の
     * 比較となり、<b>本番だけ SQL が例外で落ちる</b>。
     * まさに本 issue が扱っている「test/local では通り本番だけ壊れるスキーマ差」の一種であり、
     * しかも符号性ではなく<b>照合順序</b>という別の軸だった。</p>
     *
     * <h2>本テストの立場</h2>
     * <p>これは<b>欠陥を正常として凍結するものではない</b>。是正（{@code notifications} の照合順序を
     * サーバ既定へ揃える migration、あるいは全テーブルの照合順序統一）は DDL 変更であり
     * 別途承認と設計判断を要するため本 PR の範囲外とし、
     * <b>事実を測定して可視化する</b>ところまでを担う。
     * 是正されればこのテストが赤くなり、そのとき本テストと {@code measurableSql()} を削除すればよい。</p>
     */
    @Test
    @DisplayName("【既知欠陥】本番RDSの照合順序設定では aggregateFolderUnreadCounts が照合不一致で失敗する")
    void 本番照合順序では実クエリが照合不一致で失敗する() throws Exception {
        long userId = seedUnreadCountFixture();

        // given: 本番 RDS と同じサーバ既定照合順序（utf8mb4_0900_ai_ci）で構築されたスキーマ
        assertThat(readColumnCollation("notifications", "scope_type"))
                .as("notifications は V4.019 で utf8mb4_unicode_ci を明示宣言している")
                .isEqualTo("utf8mb4_unicode_ci");
        assertThat(readColumnCollation("my_scope_folders", "scope_type"))
                .as("my_scope_folders は宣言せずサーバ既定に従う（本コンテナ = 本番 RDS と同じ 0900_ai_ci）")
                .isEqualTo("utf8mb4_0900_ai_ci");

        // when / then: リポジトリの SQL をそのまま走らせると本番と同じ例外で落ちる
        String rawSql = repositorySql();
        assertThatThrownBy(() -> runUnreadCounts(rawSql, userId))
                .as("照合順序不一致により本番では当該 API が 500 になる（要是正・本 PR 範囲外）")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Illegal mix of collations");
    }

    // =====================================================================
    // フィクスチャ / ヘルパ
    // =====================================================================

    /**
     * 本測定に無関係な既知欠陥（{@code scope_type} の照合順序不一致。下記 §参照）だけを中和した SQL を返す。
     *
     * <p><b>これは症状の握りつぶしではない。</b> 照合順序不一致は本 IT が新たに発見した
     * <b>別個の本番欠陥</b>であり、{@link #本番照合順序では実クエリが照合不一致で失敗する()} が
     * 事実として測定・記録している。本メソッドはその欠陥を「測定対象外の既知ノイズ」として
     * 明示的に除去し、{@code scope_id} の符号性という本来の測定対象に到達させるためのものである。
     * 照合順序欠陥が是正されたら本メソッドは不要になり削除できる。</p>
     */
    private static String measurableSql() throws NoSuchMethodException {
        String sql = repositorySql();
        String neutralized = sql.replace(
                "n.scope_type = folder.scope_type",
                "n.scope_type = folder.scope_type COLLATE utf8mb4_unicode_ci");
        assertThat(neutralized)
                .as("照合順序の中和が空振りしていないこと（空振りすると本番と同じ例外で落ちる）")
                .isNotEqualTo(sql);
        return neutralized;
    }

    /** {@code aggregateFolderUnreadCounts} の SQL をリポジトリの {@link Query} から反射で取り出す。 */
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
