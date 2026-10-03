package com.mannschaft.app.common.migration;

import com.mannschaft.app.common.storage.migration.StorageMigrationErrorEntity;
import com.mannschaft.app.common.storage.quota.entity.StorageUsageLogEntity;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 既存の schedule media と storage 監査行を UUIDv7 主キーへ移行する MySQL/Flyway 契約。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.common.migration.FlywayExistingDataScheduleMediaUuidMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存 schedule media データの UUIDv7 主キー移行")
class FlywayExistingDataScheduleMediaUuidMigrationTest {

    private static final String PRE_V215_TARGET = "214.20260916131000";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_schedule_media_uuid")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(java.util.Map.of("/var/lib/mysql", "rw"))
            .withCommand("--log_bin_trust_function_creators=1");

    public static boolean isDockerAvailable() {
        try {
            return "true".equalsIgnoreCase(System.getenv("CI"))
                    || DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception exception) {
            return "true".equalsIgnoreCase(System.getenv("CI"));
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

    @Test
    @DisplayName("既存行・監査参照・ACL binding を保全して UUIDv7 主キーへ交換する")
    void migratesExistingScheduleMediaAndStorageReferences() throws Exception {
        flyway(MigrationVersion.fromVersion(PRE_V215_TARGET)).migrate();
        seedLegacyRows();

        assertThat(flyway(null).migrate().success).isTrue();

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            List<UUID> mediaIds = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery("SELECT HEX(id) FROM schedule_media_uploads ORDER BY r2_key")) {
                while (rs.next()) {
                    mediaIds.add(uuidFromHex(rs.getString(1)));
                }
            }
            assertThat(mediaIds).hasSize(2).doesNotHaveDuplicates();
            assertThat(mediaIds).allSatisfy(id -> {
                assertThat(id.version()).isEqualTo(7);
                assertThat(id.variant()).isEqualTo(2);
            });
            assertThat(singleLong(statement, "SELECT COUNT(*) FROM schedule_media_uploads WHERE r2_key IN "
                    + "('schedules/1/10/legacy-a', 'schedules/1/10/legacy-b')")).isEqualTo(2);
            assertThat(singleLong(statement, "SELECT COUNT(*) FROM schedule_media_uploads "
                    + "WHERE schedule_id IS NULL AND uploader_id IS NULL")).isEqualTo(2);
            assertThat(singleLong(statement, "SELECT COUNT(*) FROM information_schema.COLUMNS "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'schedule_media_uploads' "
                    + "AND COLUMN_NAME = 'id' AND COLUMN_TYPE = 'binary(16)' AND COLUMN_KEY = 'PRI' "
                    + "AND EXTRA NOT LIKE '%auto_increment%'")).isEqualTo(1);
            assertThat(singleLong(statement, "SELECT COUNT(*) FROM information_schema.COLUMNS "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'schedule_media_uploads' "
                    + "AND COLUMN_NAME = 'id_uuid'")).isZero();

            String mediaAUuid = singleString(statement, "SELECT LOWER(BIN_TO_UUID(id)) FROM schedule_media_uploads "
                    + "WHERE r2_key = 'schedules/1/10/legacy-a'");
            assertThat(singleString(statement, "SELECT LOWER(HEX(reference_uuid)) FROM storage_usage_logs "
                    + "WHERE reference_type = 'schedule_media_uploads' AND reference_id = 101"))
                    .isEqualTo(mediaAUuid.replace("-", ""));
            assertThat(singleString(statement, "SELECT LOWER(HEX(reference_uuid)) FROM storage_migration_errors "
                    + "WHERE reference_type = 'schedule_media_uploads' AND reference_id = 101"))
                    .isEqualTo(mediaAUuid.replace("-", ""));
            assertThat(singleString(statement, "SELECT attachment_binding_key FROM storage_acls "
                    + "WHERE file_key = 'schedules/1/10/legacy-a'")).isEqualTo(mediaAUuid);
            assertThat(singleLong(statement, "SELECT reference_id FROM storage_usage_logs "
                    + "WHERE reference_type = 'schedule_media_uploads' AND reference_id = 999999")).isEqualTo(999999L);
            assertThat(singleLong(statement, "SELECT COUNT(*) FROM storage_usage_logs "
                    + "WHERE reference_type = 'schedule_media_uploads' AND reference_id = 999999 "
                    + "AND reference_uuid IS NULL")).isEqualTo(1);
            assertThat(indexExists(statement, "storage_usage_logs", "idx_sul_reference_uuid")).isTrue();
            assertThat(indexExists(statement, "storage_migration_errors", "idx_sme_reference_uuid")).isTrue();
        }

        assertUuidReferenceJpaPersistence();
    }

    private void seedLegacyRows() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO schedule_media_uploads
                        (id, schedule_id, uploader_id, media_type, r2_key, thumbnail_r2_key, file_name,
                         file_size, content_type, duration_seconds, caption, taken_at, is_cover,
                         is_expense_receipt, processing_status, created_at)
                    VALUES
                        (101, NULL, NULL, 'IMAGE', 'schedules/1/10/legacy-a', NULL, 'legacy-a.png',
                         101, 'image/png', NULL, 'caption-a', NULL, FALSE, FALSE, 'READY', UTC_TIMESTAMP()),
                        (102, NULL, NULL, 'IMAGE', 'schedules/1/10/legacy-b', NULL, 'legacy-b.png',
                         102, 'image/png', NULL, 'caption-b', NULL, TRUE, FALSE, 'READY', UTC_TIMESTAMP())
                    """);
            statement.executeUpdate("""
                    INSERT INTO storage_subscriptions
                        (id, scope_type, scope_id, plan_id, used_bytes, file_count, created_at, updated_at)
                    VALUES (901, 'TEAM', 902, 1, 0, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP())
                    """);
            statement.executeUpdate("""
                    INSERT INTO storage_usage_logs
                        (subscription_id, delta_bytes, after_bytes, feature_type, reference_type, reference_id,
                         action, actor_id, created_at)
                    VALUES
                        (901, 101, 101, 'SCHEDULE', 'schedule_media_uploads', 101, 'UPLOAD', NULL, UTC_TIMESTAMP()),
                        (901, -1, 100, 'SCHEDULE', 'schedule_media_uploads', 999999, 'DELETE', NULL, UTC_TIMESTAMP())
                    """);
            statement.executeUpdate("""
                    INSERT INTO storage_migration_errors
                        (reference_type, reference_id, old_file_key, new_file_key, error_message, retry_count, created_at)
                    VALUES ('schedule_media_uploads', 101, 'old/legacy-a', 'schedules/1/10/legacy-a', 'retry', 1, UTC_TIMESTAMP())
                    """);
            statement.executeUpdate("""
                    INSERT INTO storage_acls
                        (id, file_key, owner_id, scope_type, scope_id, scope_key, acl_mode, content_type,
                         status, expires_at, created_at, updated_at, attachment_binding_type, attachment_binding_key)
                    VALUES
                        (UUID_TO_BIN(UUID()), 'schedules/1/10/legacy-a', 10, 'TEAM', NULL, '902', 'CONTENT_BOUND',
                         'image/png', 'CLAIMED', DATE_ADD(UTC_TIMESTAMP(), INTERVAL 1 HOUR), UTC_TIMESTAMP(),
                         UTC_TIMESTAMP(), 'SCHEDULE_MEDIA_UPLOAD', '101')
                    """);
        }
    }

    private Flyway flyway(MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private void assertUuidReferenceJpaPersistence() {
        var registry = new StandardServiceRegistryBuilder()
                .applySetting("jakarta.persistence.jdbc.url", MYSQL.getJdbcUrl())
                .applySetting("jakarta.persistence.jdbc.user", MYSQL.getUsername())
                .applySetting("jakarta.persistence.jdbc.password", MYSQL.getPassword())
                .applySetting("jakarta.persistence.jdbc.driver", "com.mysql.cj.jdbc.Driver")
                .applySetting("hibernate.hbm2ddl.auto", "validate")
                .applySetting("hibernate.physical_naming_strategy",
                        "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                .build();
        try (SessionFactory sessionFactory = new MetadataSources(registry)
                .addAnnotatedClass(StorageUsageLogEntity.class)
                .addAnnotatedClass(StorageMigrationErrorEntity.class)
                .buildMetadata()
                .buildSessionFactory()) {
            UUID referenceUuid = UUID.fromString("019954cc-1a40-7000-8000-000000000301");
            Long[] ids = new Long[2];
            try (var session = sessionFactory.openSession()) {
                var transaction = session.beginTransaction();
                StorageUsageLogEntity usage = StorageUsageLogEntity.builder()
                        .subscriptionId(901L)
                        .deltaBytes(1L)
                        .afterBytes(1L)
                        .featureType("SCHEDULE_MEDIA")
                        .referenceType("schedule_media_uploads")
                        .referenceUuid(referenceUuid)
                        .action("UPLOAD")
                        .build();
                StorageMigrationErrorEntity error = StorageMigrationErrorEntity.builder()
                        .referenceType("schedule_media_uploads")
                        .referenceUuid(referenceUuid)
                        .oldFileKey("old/new-row")
                        .newFileKey("schedules/new-row")
                        .errorMessage("retry")
                        .build();
                session.persist(usage);
                session.persist(error);
                session.flush();
                ids[0] = usage.getId();
                ids[1] = error.getId();
                transaction.commit();
            }
            try (var session = sessionFactory.openSession()) {
                StorageUsageLogEntity usage = session.find(StorageUsageLogEntity.class, ids[0]);
                StorageMigrationErrorEntity error = session.find(StorageMigrationErrorEntity.class, ids[1]);
                assertThat(usage.getReferenceId()).isNull();
                assertThat(usage.getReferenceUuid()).isEqualTo(referenceUuid);
                assertThat(error.getReferenceId()).isNull();
                assertThat(error.getReferenceUuid()).isEqualTo(referenceUuid);
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private static long singleLong(Statement statement, String sql) throws Exception {
        try (ResultSet rs = statement.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }

    private static String singleString(Statement statement, String sql) throws Exception {
        try (ResultSet rs = statement.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private static boolean indexExists(Statement statement, String table, String index) throws Exception {
        return singleLong(statement, "SELECT COUNT(*) FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "' AND INDEX_NAME = '" + index + "'") > 0;
    }

    private static UUID uuidFromHex(String hex) {
        String value = hex.toLowerCase();
        return UUID.fromString(value.substring(0, 8) + "-" + value.substring(8, 12) + "-"
                + value.substring(12, 16) + "-" + value.substring(16, 20) + "-" + value.substring(20));
    }
}
