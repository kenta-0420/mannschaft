package com.mannschaft.app.actionmemo.persistence;

import com.mannschaft.app.actionmemo.entity.ActionMemoTagEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * sort_orderの実Flyway型検証とunsigned上半分のInteger読み書きを固定する。
 *
 * <p>管理Entityはタグ一つだけ。実在する専用所有ユーザーを新規IDで作成し、
 * 業務BeanやUserEntity全体を起動しない。失敗書込は各独立TXでrollbackする。</p>
 */
@SpringBootTest(classes = ActionMemoTagFlywaySchemaIT.MappingConfiguration.class, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.docker.compose.enabled=false"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIf("com.mannschaft.app.actionmemo.persistence.ActionMemoTagFlywaySchemaIT#isDockerAvailable")
@DisplayName("行動メモタグsort_orderのunsigned範囲と既存値保持（実Flyway/MySQL）")
@Timeout(120)
class ActionMemoTagFlywaySchemaIT {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class MappingConfiguration {
        @Bean
        PersistenceManagedTypes persistenceManagedTypes() {
            return PersistenceManagedTypes.of(ActionMemoTagEntity.class.getName());
        }
    }

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("action_memo_sort_order_contract")
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

    @AfterAll
    static void stopOwnedMySql() {
        if (MYSQL.isRunning()) {
            MYSQL.stop();
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
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
    private Long ownedUserId;
    private String ownedEmail;
    private final List<Long> ownedTags = new ArrayList<>();

    @BeforeEach
    void verifySchemaAndCreateOwnedParent() {
        transactions = new TransactionTemplate(transactionManager);
        assertThat(jdbc.queryForObject("SELECT COLUMN_TYPE FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name='action_memo_tags' AND column_name='sort_order'",
                String.class)).isEqualTo("smallint unsigned");
        assertThat(jdbc.queryForObject("SELECT @@SESSION.sql_mode", String.class)).contains("STRICT_TRANS_TABLES");
        ownedEmail = "sort-order-" + UUID.randomUUID() + "@example.invalid";
        // users INSERT金型で専用所有者を作る。V99.001でFKは削除済み。認証codecの試験ではない。
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("INSERT INTO users "
                    + "(email,last_name,first_name,display_name,status,created_at,updated_at) "
                    + "VALUES(?,'並び順','専用親','並び順専用親','ACTIVE',NOW(),NOW())", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, ownedEmail);
            return statement;
        }, key);
        ownedUserId = key.getKey().longValue();
    }

    @AfterEach
    void cleanupOwnedRows() {
        for (Long tagId : ownedTags) {
            assertThat(jdbc.update("DELETE FROM action_memo_tags WHERE id=? AND user_id=?", tagId, ownedUserId))
                    .isEqualTo(1);
        }
        ownedTags.clear();
        if (ownedUserId != null) {
            assertThat(ownedCount()).isZero();
            assertThat(jdbc.update("DELETE FROM users WHERE id=? AND email=?", ownedUserId, ownedEmail)).isEqualTo(1);
        }
    }

    @ParameterizedTest(name = "sort_order={0}: Integer保存取得")
    @ValueSource(ints = {0, 32767, 32768, 65535})
    void 保存取得_unsigned境界値_Integerで同値を保持する(int value) {
        Long id = persist(value == 0 ? null : value);
        transactions.executeWithoutResult(status -> {
            em.clear();
            assertThat(em.find(ActionMemoTagEntity.class, id).getSortOrder()).isEqualTo(value);
        });
        assertThat(rawOrder(id)).isEqualTo(value);
        if (value == 0) {
            assertThat(rawOrder(insertRaw(null))).isZero();
        }
    }

    @ParameterizedTest(name = "既存sort_order={0}: nameのみ更新")
    @ValueSource(ints = {32768, 65535})
    void 既存行更新_unsigned上半分_name変更後も値を保持する(int value) {
        Long id = insertRaw(value);
        transactions.executeWithoutResult(status -> {
            em.clear();
            ActionMemoTagEntity tag = em.find(ActionMemoTagEntity.class, id);
            assertThat(tag.getSortOrder()).isEqualTo(value);
            tag.setName("更新済み専用タグ");
            em.flush();
            em.clear();
            assertThat(em.find(ActionMemoTagEntity.class, id).getSortOrder()).isEqualTo(value);
        });
        assertThat(rawOrder(id)).isEqualTo(value);
        assertThat(jdbc.queryForObject("SELECT name FROM action_memo_tags WHERE id=? AND user_id=?",
                String.class, id, ownedUserId)).isEqualTo("更新済み専用タグ");
    }

    @Test
    void 保存拒否_六万五千五百三十六_独立TXだけrollbackし上位値を保持する() {
        Long existing = persist(65535);
        int before = ownedCount();
        assertThatThrownBy(() -> persist(65536)).hasRootCauseInstanceOf(SQLException.class)
                .satisfies(error -> assertThat(((SQLException) NestedExceptionUtils.getMostSpecificCause(error))
                        .getErrorCode()).isEqualTo(1264));
        assertThat(ownedCount()).isEqualTo(before);
        assertThat(rawOrder(existing)).isEqualTo(65535);
    }

    @Test
    void 保存拒否_SQLnull_独立TXだけrollbackし上位値を保持する() {
        Long existing = persist(32768);
        int before = ownedCount();
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                jdbc.update("UPDATE action_memo_tags SET sort_order=NULL WHERE id=? AND user_id=?",
                        existing, ownedUserId))).hasRootCauseInstanceOf(SQLException.class)
                .satisfies(error -> assertThat(((SQLException) NestedExceptionUtils.getMostSpecificCause(error))
                        .getErrorCode()).isEqualTo(1048));
        assertThat(ownedCount()).isEqualTo(before);
        assertThat(rawOrder(existing)).isEqualTo(32768);
    }

    private Long persist(Integer value) {
        Long id = transactions.execute(status -> {
            ActionMemoTagEntity.ActionMemoTagEntityBuilder<?, ?> builder = ActionMemoTagEntity.builder()
                    .userId(ownedUserId).name("専用タグ-" + UUID.randomUUID());
            if (value != null) {
                builder.sortOrder(value);
            }
            ActionMemoTagEntity tag = builder.build();
            em.persist(tag);
            em.flush();
            em.clear();
            return tag.getId();
        });
        ownedTags.add(id);
        return id;
    }

    private Long insertRaw(Integer value) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            String columns = value == null ? "" : ",sort_order";
            String values = value == null ? "" : ",?";
            PreparedStatement statement = connection.prepareStatement("INSERT INTO action_memo_tags "
                    + "(user_id,name" + columns + ",created_at,updated_at) VALUES(?,?" + values + ",NOW(),NOW())",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, ownedUserId);
            statement.setString(2, "既存専用タグ-" + UUID.randomUUID());
            if (value != null) {
                statement.setInt(3, value);
            }
            return statement;
        }, key);
        Long id = key.getKey().longValue();
        ownedTags.add(id);
        return id;
    }

    private int rawOrder(Long id) {
        return jdbc.queryForObject("SELECT sort_order FROM action_memo_tags WHERE id=? AND user_id=?",
                Integer.class, id, ownedUserId);
    }

    private int ownedCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM action_memo_tags WHERE user_id=?", Integer.class, ownedUserId);
    }
}
