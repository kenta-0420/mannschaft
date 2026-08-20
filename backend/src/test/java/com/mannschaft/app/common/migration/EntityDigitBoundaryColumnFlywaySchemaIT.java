package com.mannschaft.app.common.migration;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * <b>数字→大文字境界（{@code s3Key} / {@code r2ObjectKey}）を持つ Entity フィールドの物理列名を
 * 実 Flyway スキーマに対して検証する結合テスト（Issue #2856）。</b>
 *
 * <h2>なぜ通常のテストで検出できないか</h2>
 * <p>{@code test} プロファイルは {@code spring.flyway.enabled=false} /
 * {@code ddl-auto=create}（Entity 由来 DDL）で走るため、Entity とマイグレーションの
 * 食い違いは自己整合して必ず緑になる。本クラスはクラス単位で
 * {@code spring.flyway.enabled=true} / {@code ddl-auto=none} に上書きし、
 * <b>実 Flyway スキーマ</b>を適用した MySQL に対してクエリを発行する。
 * （前例: {@code com.mannschaft.app.proxy.ProxyInputConsentS3KeyFlywaySchemaTest}）</p>
 *
 * <h2>何を測るか</h2>
 * <p>Spring Boot の既定物理命名戦略は「数字の直後の大文字」に区切りを入れないため、
 * {@code @Column(name=...)} を省略した {@code s3Key} は {@code s3key} という列名で
 * SQL に埋め込まれる。Flyway の実列名は {@code s3_key} なので、JPQL を 1 本流すだけで
 * {@code Unknown column '…s3key'} が発生する。行を INSERT する必要が無いため FK 依存も無い。</p>
 *
 * <p>Docker 未起動環境では {@code @EnabledIf} でスキップされる（骨抜きにしない・根治原則）。
 * 静的な再発防止は
 * {@code com.mannschaft.app.common.architecture.EntityDigitBoundaryColumnNameGuardTest} が担う。</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none"
})
@ActiveProfiles("test")
@Testcontainers
@Transactional
@EnabledIf("com.mannschaft.app.common.migration.EntityDigitBoundaryColumnFlywaySchemaIT#isDockerAvailable")
@DisplayName("数字→大文字境界フィールドの物理列名 Flyway 実スキーマ整合テスト")
class EntityDigitBoundaryColumnFlywaySchemaIT {

    /** Flyway スキーマ適用用 MySQL コンテナ（tmpfs は WSL2 VHD 遅延回避）。 */
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_digit_boundary_col")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCommand("--log_bin_trust_function_creators=1");

    static {
        if (isDockerAvailable()) {
            MYSQL.start();
            awaitRealConnectivity();
        }
    }

    /**
     * MySQL の公式イメージは初期化中に一時サーバーを立てるため、コンテナ起動直後は
     * ポートが開いていても実際のハンドシェイクが完了せず、Spring 側の初回接続が
     * {@code Communications link failure}（socket handshake timeout）で落ちることがある
     * （WSL2 + tmpfs 環境で再現）。Spring context 生成前に実接続が成立するまで待つ。
     */
    private static void awaitRealConnectivity() {
        long deadline = System.currentTimeMillis() + 180_000L;
        RuntimeException last = null;
        while (System.currentTimeMillis() < deadline) {
            try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                    MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                 java.sql.Statement st = c.createStatement()) {
                st.execute("SELECT 1");
                return;
            } catch (Exception e) {
                last = new IllegalStateException("MySQL への実接続がまだ成立しない", e);
                try {
                    Thread.sleep(2_000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ie);
                }
            }
        }
        throw last != null ? last : new IllegalStateException("MySQL への実接続がタイムアウトした");
    }

    /** Redis は外部依存のためモック化。 */
    @MockitoBean
    org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    @PersistenceContext
    private EntityManager em;

    /**
     * 各 Entity の該当フィールドを JPQL で射影し、実 Flyway スキーマに存在する列名で
     * SQL が発行されていることを確認する。列名が食い違っていれば
     * {@code Unknown column} で例外になる。
     */
    @ParameterizedTest(name = "{0}#{1} の物理列名が Flyway 実スキーマと一致する")
    @CsvSource({
            "DataExportEntity, s3Key",
            "ChartPhotoEntity, s3Key",
            "DirectMailImageUploadEntity, s3Key",
            "EquipmentItemEntity, s3Key",
            "KbImageUploadEntity, s3Key",
            "ResidentDocumentEntity, s3Key",
            "TimetableSlotUserNoteAttachmentEntity, r2ObjectKey"
    })
    @DisplayName("数字→大文字境界フィールドの JPQL 射影が実 Flyway スキーマで成功する")
    void digitBoundaryColumnsMatchFlywaySchema(String entityName, String fieldName) {
        String jpql = "select e." + fieldName + " from " + entityName + " e";
        assertThatCode(() -> em.createQuery(jpql).setMaxResults(1).getResultList())
                .as("%s の物理列名が Flyway 実スキーマの列名と一致しない場合、"
                        + "Unknown column で失敗する（Issue #2856）", entityName + "#" + fieldName)
                .doesNotThrowAnyException();
    }

    /**
     * Issue #2856 の被害経路（{@code data_exports} のクリーンアップ用クエリ）を
     * 実スキーマで直接踏む。列名 {@code s3_key} を条件・射影の双方で使う。
     */
    @Test
    @DisplayName("data_exports の s3_key を条件に含むクエリが実 Flyway スキーマで成功する")
    void dataExportsS3KeyQueryWorksOnFlywaySchema() {
        List<?> rows = em.createQuery(
                        "select e.id from DataExportEntity e where e.s3Key is not null")
                .setMaxResults(1)
                .getResultList();
        assertThat(rows).as("クエリが実行できること（件数は問わない）").isNotNull();

        @SuppressWarnings("unchecked")
        List<Object> native_ = em.createNativeQuery(
                        "SELECT s3_key FROM data_exports LIMIT 1").getResultList();
        assertThat(native_).as("Flyway の実列名 s3_key が存在すること").isNotNull();
    }
}
