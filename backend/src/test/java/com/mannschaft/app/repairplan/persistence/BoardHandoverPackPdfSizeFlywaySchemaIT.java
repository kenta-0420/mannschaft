package com.mannschaft.app.repairplan.persistence;

import com.mannschaft.app.repairplan.entity.BoardHandoverPack;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.flywaydb.core.Flyway;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 実Flywayスキーマでpdf_sizeのLong全域・更新・null・範囲外rollbackを確認する。
 * 共有MySQLは停止せず、専用DBだけを所有して適用・破棄する。Spring contextは作らない。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.repairplan.persistence.BoardHandoverPackPdfSizeFlywaySchemaIT#requiresMySql")
@Timeout(120)
@DisplayName("引継ぎPDFサイズ: 実MySQLのINT UNSIGNEDとLongの保存契約")
class BoardHandoverPackPdfSizeFlywaySchemaIT {

    private static final String DATABASE_PREFIX = "pdf_size_it_";

    private MySQLContainer<?> mysql;
    private String database;
    private String rootPassword;
    private String dedicatedUrl;
    private boolean databaseCreated;
    private StandardServiceRegistry registry;
    private SessionFactory sessionFactory;

    static boolean requiresMySql() {
        return "true".equalsIgnoreCase(System.getenv("CI"))
                || AbstractMySqlIntegrationTest.isDockerAvailable();
    }

    @BeforeAll
    @Timeout(600)
    void migrateOwnedDatabaseAndValidateRealEntity() throws Exception {
        mysql = AbstractMySqlIntegrationTest.sharedMySqlContainer();
        assertThat(mysql.isRunning()).as("CIでDockerが使えない場合もskipせず起動失敗を示す").isTrue();
        rootPassword = mysql.getEnvMap().get("MYSQL_ROOT_PASSWORD");
        assertThat(rootPassword).as("起動後の実MYSQL_ROOT_PASSWORDが必要").isNotBlank();
        database = DATABASE_PREFIX + UUID.randomUUID().toString().replace("-", "");
        verifyOwnedDatabase();
        String sharedUrl = mysql.getJdbcUrl();
        String sharedPath = "/" + mysql.getDatabaseName();
        int pathStart = sharedUrl.indexOf(sharedPath);
        assertThat(pathStart).as("共有JDBC URLに実DB名がある").isGreaterThanOrEqualTo(0);
        int pathEnd = pathStart + sharedPath.length();
        assertThat(pathEnd == sharedUrl.length() || sharedUrl.charAt(pathEnd) == '?')
                .as("DB名全体だけを置換する").isTrue();
        dedicatedUrl = sharedUrl.substring(0, pathStart) + "/" + database + sharedUrl.substring(pathEnd);
        try {
            try (Connection connection = rootConnection(false); Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE DATABASE `" + database + "`");
                databaseCreated = true;
            }
            Flyway.configure()
                    .dataSource(dedicatedUrl, "root", rootPassword)
                    .locations("classpath:db/migration")
                    .outOfOrder(false)
                    .load()
                    .migrate();
            registry = new StandardServiceRegistryBuilder()
                    .applySetting("jakarta.persistence.jdbc.url", dedicatedUrl)
                    .applySetting("jakarta.persistence.jdbc.user", "root")
                    .applySetting("jakarta.persistence.jdbc.password", rootPassword)
                    .applySetting("jakarta.persistence.jdbc.driver", "com.mysql.cj.jdbc.Driver")
                    .applySetting("hibernate.hbm2ddl.auto", "validate")
                    .applySetting("hibernate.physical_naming_strategy",
                            "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                    .build();
            sessionFactory = new MetadataSources(registry)
                    .addAnnotatedClass(BoardHandoverPack.class)
                    .buildMetadata()
                    .buildSessionFactory();
        } catch (Exception | AssertionError failure) {
            try {
                closeOwnedResources();
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    @AfterAll
    @Timeout(30)
    void closeOwnedResources() throws Exception {
        try {
            if (sessionFactory != null) {
                sessionFactory.close();
                sessionFactory = null;
            }
        } finally {
            try {
                if (registry != null) {
                    StandardServiceRegistryBuilder.destroy(registry);
                    registry = null;
                }
            } finally {
                if (databaseCreated) {
                    verifyOwnedDatabase();
                    try (Connection connection = rootConnection(false);
                         Statement statement = connection.createStatement()) {
                        statement.executeUpdate("DROP DATABASE `" + database + "`");
                        databaseCreated = false;
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("正本Flywayのnullable INT UNSIGNEDを実JDBC metadataとvalidateで確認する")
    void validatesActualUnsignedNullableColumn() throws Exception {
        assertThat(sessionFactory.isOpen()).isTrue();
        try (Connection connection = rootConnection(true)) {
            try (ResultSet columns = connection.getMetaData()
                    .getColumns(database, null, "board_handover_packs", "pdf_size")) {
                assertThat(columns.next()).isTrue();
                assertThat(columns.getInt("DATA_TYPE")).isEqualTo(Types.INTEGER);
                assertThat(columns.getString("TYPE_NAME")).isEqualToIgnoringCase("INT UNSIGNED");
                assertThat(columns.getInt("NULLABLE")).isEqualTo(DatabaseMetaData.columnNullable);
                assertThat(columns.next()).isFalse();
            }
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT COLUMN_TYPE, IS_NULLABLE FROM information_schema.COLUMNS"
                                 + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'board_handover_packs'"
                                 + " AND COLUMN_NAME = 'pdf_size'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("int unsigned");
                assertThat(result.getString(2)).isEqualTo("YES");
            }
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT @@SESSION.sql_mode")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).contains("STRICT_TRANS_TABLES");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 2147483647L, 2147483648L, 4294967295L})
    @DisplayName("Longの上下半分を保存commitし別SessionとJDBCで正確に再取得する")
    void persistsAndReloadsFullUnsignedRange(long value) throws Exception {
        UUID id = inTransaction(session -> {
            BoardHandoverPack pack = fixture(value);
            session.persist(pack);
            session.flush();
            return pack.getId();
        });
        Long reloadedSize = inTransaction(session -> session.find(BoardHandoverPack.class, id).getPdfSize());
        assertThat(reloadedSize).isEqualTo(value);
        assertRawSize(id, value);
    }

    @ParameterizedTest
    @ValueSource(longs = {2147483648L, 4294967295L})
    @DisplayName("既存上半分行の無関連列を更新してもpdf_sizeを縮小しない")
    void preservesExistingUpperHalfOnUnrelatedUpdate(long value) throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection connection = rootConnection(true);
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO board_handover_packs"
                             + " (id, organization_id, scope_type, scope_id, term_year, period_start, period_end,"
                             + " pii_level, status, generated_by, password_separately_sent, version,"
                             + " created_at, updated_at, pdf_size)"
                             + " VALUES (UUID_TO_BIN(?), 1, 'ORGANIZATION', 1, 2026, '2026-01-01', '2026-12-31',"
                             + " 'STANDARD', 'GENERATING', 1, 0, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP(), ?)")) {
            statement.setString(1, id.toString());
            statement.setLong(2, value);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
        inTransaction(session -> {
            BoardHandoverPack pack = session.find(BoardHandoverPack.class, id);
            assertThat(pack.getPdfSize()).isEqualTo(value);
            pack.setPdfR2Key("pdf-size-contract/updated");
            session.flush();
            return null;
        });
        inTransaction(session -> {
            BoardHandoverPack pack = session.find(BoardHandoverPack.class, id);
            assertThat(pack.getPdfSize()).isEqualTo(value);
            assertThat(pack.getPdfR2Key()).isEqualTo("pdf-size-contract/updated");
            assertThat(pack.getVersion()).isEqualTo(1L);
            return null;
        });
        assertRawSize(id, value);
    }

    @Test
    @DisplayName("nullable値を保存し無関連列を更新してもnullを保持する")
    void preservesNullAcrossPersistenceAndUpdate() throws Exception {
        UUID id = inTransaction(session -> {
            BoardHandoverPack pack = fixture(null);
            session.persist(pack);
            session.flush();
            return pack.getId();
        });
        inTransaction(session -> {
            BoardHandoverPack pack = session.find(BoardHandoverPack.class, id);
            assertThat(pack.getPdfSize()).isNull();
            pack.setPdfR2Key("pdf-size-contract/null");
            session.flush();
            return null;
        });
        Long reloadedSize = inTransaction(session -> session.find(BoardHandoverPack.class, id).getPdfSize());
        assertThat(reloadedSize).isNull();
        assertRawSize(id, null);
    }

    @Test
    @DisplayName("4294967296はMySQLが拒否しrollback後も既存最大値行を保全する")
    void rejectsOverflowAndPreservesCommittedRow() throws Exception {
        UUID existingId = inTransaction(session -> {
            BoardHandoverPack pack = fixture(4294967295L);
            session.persist(pack);
            session.flush();
            return pack.getId();
        });
        UUID rejectedId = UUID.randomUUID();
        try (Session session = sessionFactory.openSession()) {
            Transaction transaction = session.beginTransaction();
            try {
                Throwable failure = catchThrowable(() -> {
                    BoardHandoverPack rejected = fixture(4294967296L);
                    rejected.setId(rejectedId);
                    session.persist(rejected);
                    session.flush();
                });
                assertThat(failure).isNotNull();
                SQLException sqlFailure = findSqlException(failure);
                assertThat((Throwable) sqlFailure).as("実MySQLの範囲外エラー").isNotNull();
                assertThat(sqlFailure.getErrorCode()).isEqualTo(1264);
                assertThat(sqlFailure.getMessage()).contains("pdf_size");
            } finally {
                if (transaction.isActive()) {
                    transaction.rollback();
                }
            }
        }
        BoardHandoverPack rejected = inTransaction(session -> session.find(BoardHandoverPack.class, rejectedId));
        Long preservedSize = inTransaction(session -> session.find(BoardHandoverPack.class, existingId).getPdfSize());
        assertThat(rejected).isNull();
        assertThat(preservedSize).isEqualTo(4294967295L);
        assertRawSize(existingId, 4294967295L);
        try (Connection connection = rootConnection(true);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM board_handover_packs WHERE id = UUID_TO_BIN(?)")) {
            statement.setString(1, rejectedId.toString());
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isZero();
            }
        }
    }

    private BoardHandoverPack fixture(Long size) {
        return BoardHandoverPack.builder()
                .organizationId(1L).scopeType("ORGANIZATION").scopeId(1L).termYear(2026)
                .periodStart(LocalDate.of(2026, 1, 1)).periodEnd(LocalDate.of(2026, 12, 31))
                .piiLevel("STANDARD").status("GENERATING").generatedBy(1L).pdfSize(size)
                .build();
    }

    private <T> T inTransaction(Function<Session, T> operation) {
        try (Session session = sessionFactory.openSession()) {
            Transaction transaction = session.beginTransaction();
            try {
                T result = operation.apply(session);
                transaction.commit();
                return result;
            } catch (RuntimeException | Error failure) {
                if (transaction.isActive()) {
                    try {
                        transaction.rollback();
                    } catch (RuntimeException rollbackFailure) {
                        failure.addSuppressed(rollbackFailure);
                    }
                }
                throw failure;
            }
        }
    }

    private void assertRawSize(UUID id, Long expected) throws Exception {
        try (Connection connection = rootConnection(true);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT pdf_size FROM board_handover_packs WHERE id = UUID_TO_BIN(?)")) {
            statement.setString(1, id.toString());
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                long value = result.getLong(1);
                if (expected == null) {
                    assertThat(result.wasNull()).isTrue();
                } else {
                    assertThat(result.wasNull()).isFalse();
                    assertThat(value).isEqualTo(expected);
                    assertThat(result.getMetaData().isSigned(1)).isFalse();
                }
                assertThat(result.next()).isFalse();
            }
        }
    }

    private static SQLException findSqlException(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return sqlException;
            }
        }
        return null;
    }

    private Connection rootConnection(boolean dedicated) throws SQLException {
        return DriverManager.getConnection(dedicated ? dedicatedUrl : mysql.getJdbcUrl(), "root", rootPassword);
    }

    private void verifyOwnedDatabase() {
        assertThat(database).matches("pdf_size_it_[0-9a-f]{32}");
        assertThat(database).isNotEqualTo(mysql.getDatabaseName());
    }
}
