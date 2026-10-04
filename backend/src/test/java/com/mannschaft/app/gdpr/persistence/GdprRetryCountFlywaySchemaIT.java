package com.mannschaft.app.gdpr.persistence;

import com.mannschaft.app.gdpr.entity.AccountPurgeCompletionStatusEntity;
import com.mannschaft.app.gdpr.entity.GdprS3PurgeFailureEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GDPRの二つのretry_countを実Flywayスキーマで検証する。
 *
 * <p>共有createスキーマではunsigned列との不一致を隠すため、専用の実MySQL一つを使う。
 * 失敗書込はそれぞれ独立TXでrollbackし、他ケースへ失敗状態を持ち越さない。</p>
 */
@SpringBootTest(classes = GdprRetryCountFlywaySchemaIT.MappingConfiguration.class, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.docker.compose.enabled=false"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIf("com.mannschaft.app.gdpr.persistence.GdprRetryCountFlywaySchemaIT#isDockerAvailable")
@DisplayName("GDPR retry_countのunsigned範囲と既存値保持（実Flyway/MySQL）")
@Timeout(120)
class GdprRetryCountFlywaySchemaIT {

    /** 業務schedulerを起動せず、二つの実Entityと正本Flyway全migrationだけを検証する。 */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class MappingConfiguration {
        @Bean
        PersistenceManagedTypes persistenceManagedTypes() {
            return PersistenceManagedTypes.of(AccountPurgeCompletionStatusEntity.class.getName(),
                    GdprS3PurgeFailureEntity.class.getName());
        }
    }

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("gdpr_retry_count_contract")
            .withUsername("test").withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw,size=512m"))
            .withCommand("--log_bin_trust_function_creators=1",
                    "--innodb_buffer_pool_size=64M", "--max_connections=24")
            .withCreateContainerCmdModifier(command -> command.getHostConfig()
                    .withMemory(1024L * 1024 * 1024).withMemorySwap(1024L * 1024 * 1024))
            .withStartupTimeout(Duration.ofSeconds(120))
            .withReuse(false);

    static {
        if (isDockerAvailable()) {
            MYSQL.start();
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @AfterAll
    static void stopOwnedMySql() {
        if (MYSQL.isRunning()) {
            MYSQL.stop();
        }
    }

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception unavailable) {
            return false;
        }
    }

    @PersistenceContext private EntityManager em;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    private TransactionTemplate transactions;
    private final List<OwnedRow> ownedRows = new ArrayList<>();

    private record OwnedRow(Kind kind, UUID id) { }

    private enum Kind {
        COMPLETION("account_purge_completion_status"), S3("gdpr_s3_purge_failures");

        private final String table;

        Kind(String table) {
            this.table = table;
        }
    }

    @BeforeEach
    void verifySchema() {
        transactions = new TransactionTemplate(transactionManager);
        for (Kind kind : Kind.values()) {
            assertThat(jdbc.queryForObject("SELECT COLUMN_TYPE FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND table_name=? AND column_name='retry_count'",
                    String.class, kind.table)).isEqualTo("tinyint unsigned");
        }
        assertThat(jdbc.queryForObject("SELECT @@SESSION.sql_mode", String.class))
                .contains("STRICT_TRANS_TABLES");
    }

    @AfterEach
    void cleanupOwnedRows() {
        for (OwnedRow row : ownedRows) {
            assertThat(jdbc.update("DELETE FROM " + row.kind().table + " WHERE id=UUID_TO_BIN(?)",
                    row.id().toString())).isEqualTo(1);
        }
        ownedRows.clear();
    }

    static Stream<Arguments> unsignedValues() {
        return Stream.of(Kind.values()).flatMap(kind -> Stream.of(0, 127, 128, 255)
                .map(value -> Arguments.of(kind, value)));
    }

    static Stream<Arguments> legacyValues() {
        return Stream.of(Kind.values()).flatMap(kind -> Stream.of(128, 255)
                .map(value -> Arguments.of(kind, value)));
    }

    @ParameterizedTest(name = "{0}: retry_count={1}")
    @MethodSource("unsignedValues")
    void 保存取得_unsigned境界値_Integerで同値を保持する(Kind kind, int value) {
        UUID id = persist(kind, value);
        transactions.executeWithoutResult(status -> {
            em.clear();
            assertThat(retryCount(find(kind, id))).isEqualTo(value);
        });
        assertThat(rawCount(kind, id)).isEqualTo(value);
    }

    @ParameterizedTest(name = "{0}: 既存retry_count={1}")
    @MethodSource("legacyValues")
    void 既存行更新_unsigned上半分をSQLで作成_別field更新後も値を保持する(Kind kind, int value) {
        UUID id = UUID.randomUUID();
        insertRaw(kind, id, value);
        transactions.executeWithoutResult(status -> {
            em.clear();
            Object entity = find(kind, id);
            assertThat(retryCount(entity)).isEqualTo(value);
            if (entity instanceof AccountPurgeCompletionStatusEntity completion) {
                completion.setStatus("SUCCESS");
            } else {
                ((GdprS3PurgeFailureEntity) entity).setLastError("retry-mapping-update");
            }
            em.flush();
            em.clear();
            assertThat(retryCount(find(kind, id))).isEqualTo(value);
        });
        assertThat(rawCount(kind, id)).isEqualTo(value);
        String column = kind == Kind.COMPLETION ? "status" : "last_error";
        String expected = kind == Kind.COMPLETION ? "SUCCESS" : "retry-mapping-update";
        assertThat(jdbc.queryForObject("SELECT " + column + " FROM " + kind.table
                + " WHERE id=UUID_TO_BIN(?)", String.class, id.toString())).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}: Entity/DDL default=0")
    @EnumSource(Kind.class)
    void 初期値_Entityと実DDL_default零を保持する(Kind kind) {
        UUID entityId = persist(kind, null);
        UUID rawId = UUID.randomUUID();
        insertRaw(kind, rawId, null);
        assertThat(rawCount(kind, entityId)).isZero();
        assertThat(rawCount(kind, rawId)).isZero();
    }

    @ParameterizedTest(name = "{0}: 256拒否/独立TX")
    @EnumSource(Kind.class)
    void 保存拒否_二百五十六_失敗TXだけrollbackし既存行を保持する(Kind kind) {
        UUID existing = persist(kind, 255);
        int before = tableCount(kind);
        assertThatThrownBy(() -> persist(kind, 256)).hasRootCauseInstanceOf(SQLException.class)
                .satisfies(error -> assertThat(((SQLException) NestedExceptionUtils.getMostSpecificCause(error))
                        .getErrorCode()).isEqualTo(1264));
        assertThat(tableCount(kind)).isEqualTo(before);
        assertThat(rawCount(kind, existing)).isEqualTo(255);
    }

    @ParameterizedTest(name = "{0}: SQLnull拒否/独立TX")
    @EnumSource(Kind.class)
    void 保存拒否_SQLnull_失敗TXだけrollbackし既存行を保持する(Kind kind) {
        UUID existing = persist(kind, 128);
        int before = tableCount(kind);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                jdbc.update("UPDATE " + kind.table + " SET retry_count=NULL WHERE id=UUID_TO_BIN(?)",
                        existing.toString()))).hasRootCauseInstanceOf(SQLException.class)
                .satisfies(error -> assertThat(((SQLException) NestedExceptionUtils.getMostSpecificCause(error))
                        .getErrorCode()).isEqualTo(1048));
        assertThat(tableCount(kind)).isEqualTo(before);
        assertThat(rawCount(kind, existing)).isEqualTo(128);
    }

    private UUID persist(Kind kind, Integer value) {
        UUID id = transactions.execute(status -> {
            if (kind == Kind.COMPLETION) {
                AccountPurgeCompletionStatusEntity entity = new AccountPurgeCompletionStatusEntity();
                entity.setUserId(1L);
                entity.setEmailHash("a".repeat(64));
                entity.setDomainName("retry-mapping");
                entity.setStatus("PENDING");
                entity.setAttemptedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
                if (value != null) {
                    entity.setRetryCount(value);
                }
                em.persist(entity);
                em.flush();
                em.clear();
                return entity.getId();
            }
            GdprS3PurgeFailureEntity entity = new GdprS3PurgeFailureEntity();
            entity.setUserId(1L);
            entity.setS3Key("retry-mapping/" + UUID.randomUUID());
            entity.setFailedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
            if (value != null) {
                entity.setRetryCount(value);
            }
            em.persist(entity);
            em.flush();
            em.clear();
            return entity.getId();
        });
        ownedRows.add(new OwnedRow(kind, id));
        return id;
    }

    private void insertRaw(Kind kind, UUID id, Integer value) {
        String retryColumn = value == null ? "" : ",retry_count";
        String retryValue = value == null ? "" : ",?";
        Object[] args = value == null ? new Object[]{id.toString()} : new Object[]{id.toString(), value};
        if (kind == Kind.COMPLETION) {
            jdbc.update("INSERT INTO account_purge_completion_status "
                    + "(id,user_id,email_hash,domain_name,status,attempted_at" + retryColumn + ") "
                    + "VALUES(UUID_TO_BIN(?),1,REPEAT('a',64),'retry-mapping','PENDING','2026-01-01 00:00:00'"
                    + retryValue + ")", args);
        } else {
            jdbc.update("INSERT INTO gdpr_s3_purge_failures (id,user_id,s3_key,failed_at" + retryColumn + ") "
                    + "VALUES(UUID_TO_BIN(?),1,'retry-mapping/raw','2026-01-01 00:00:00'"
                    + retryValue + ")", args);
        }
        ownedRows.add(new OwnedRow(kind, id));
    }

    private Object find(Kind kind, UUID id) {
        return kind == Kind.COMPLETION ? em.find(AccountPurgeCompletionStatusEntity.class, id)
                : em.find(GdprS3PurgeFailureEntity.class, id);
    }

    private int retryCount(Object entity) {
        return entity instanceof AccountPurgeCompletionStatusEntity completion ? completion.getRetryCount()
                : ((GdprS3PurgeFailureEntity) entity).getRetryCount();
    }

    private int rawCount(Kind kind, UUID id) {
        return jdbc.queryForObject("SELECT retry_count FROM " + kind.table + " WHERE id=UUID_TO_BIN(?)",
                Integer.class, id.toString());
    }

    private int tableCount(Kind kind) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + kind.table, Integer.class);
    }
}
