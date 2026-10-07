package com.mannschaft.app.common.migration;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.shift.ChangeRequestType;
import com.mannschaft.app.shift.entity.ShiftAssignmentEntity;
import com.mannschaft.app.shift.entity.ShiftAssignmentRunEntity;
import com.mannschaft.app.shift.entity.ShiftChangeRequestEntity;
import com.mannschaft.app.shift.entity.ShiftSwapRequestEntity;
import jakarta.persistence.OptimisticLockException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.dialect.MySQLDialect;
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * シフト4表のversion型修正を、実Flywayの既存行と実Hibernateの更新で検証する。
 * コンテナは持たず、FlywayFromScratchMigrationTestのMySQLへ相乗りする。
 * JDBC境界値検証とHibernate @Versionの実行時型解決を区別して裏取りする。
 * 旧schemaのINT UNSIGNED列は現EntityのBIGINT宣言では再現できないため、移行前だけJDBCで作る。
 * 対象はMIGRATION_SCRIPTとENTITIESの4表、id=FIRST_IDから5行だけで、全列を移行前後に比較する。
 * 最終schemaの境界値試験はEntity.builder/実Hibernate persistで作り、生成IDの所有行だけ操作する。
 */
final class ShiftVersionBigintMigrationFixture implements Callback {

    private static final String MIGRATION_SCRIPT = "V236.20261007020941__align_shift_version_columns_to_bigint.sql";
    private static final long FIRST_ID = 9_236_000L;
    private static final List<Long> OLD_VERSIONS = List.of(0L, 2_147_483_647L, 2_147_483_648L,
            4_294_967_294L, 4_294_967_295L);
    private static final List<Long> UPDATE_VERSIONS = List.of(0L, 2_147_483_647L, 2_147_483_648L,
            4_294_967_294L, 4_294_967_295L, Long.MAX_VALUE - 1);
    private static final Map<String, Class<?>> ENTITIES = Map.of(
            "shift_assignments", ShiftAssignmentEntity.class,
            "shift_assignment_runs", ShiftAssignmentRunEntity.class,
            "shift_change_requests", ShiftChangeRequestEntity.class,
            "shift_swap_requests", ShiftSwapRequestEntity.class);
    private Map<String, List<List<String>>> beforeMigration;
    private UpgradeEvidence upgradeEvidence;

    /** 初回callbackの全列証拠だけを保持する。同じDBへの後続no-opは移行の再実行証拠とは扱わない。 */
    private record UpgradeEvidence(String jdbcUrl, String catalog, Map<String, List<List<String>>> before,
                                   Map<String, List<List<String>>> after) {
    }

    void resetForContainer() {
        beforeMigration = null;
        upgradeEvidence = null;
    }

    @Override
    public boolean supports(Event event, Context context) {
        return (event == Event.BEFORE_EACH_MIGRATE || event == Event.AFTER_EACH_MIGRATE)
                && context.getMigrationInfo() != null
                && MIGRATION_SCRIPT.equals(context.getMigrationInfo().getScript());
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public String getCallbackName() {
        return "ShiftVersionBigintMigrationFixture";
    }

    @Override
    public void handle(Event event, Context context) {
        Connection connection = context.getConnection();
        try {
            if (event == Event.BEFORE_EACH_MIGRATE) {
                resetForContainer();
                assertColumnDefinitions(connection, "int unsigned");
                withForeignKeysDisabled(connection, () -> {
                    for (String table : ENTITIES.keySet()) {
                        for (int index = 0; index < OLD_VERSIONS.size(); index++) {
                            seed(connection, table, FIRST_ID + index, OLD_VERSIONS.get(index));
                        }
                    }
                });
                beforeMigration = snapshot(connection);
            } else {
                assertColumnDefinitions(connection, "bigint");
                Map<String, List<List<String>>> after = snapshot(connection);
                assertThat(after).as("4表の既存全列をJDBC byte列で比較する").isEqualTo(beforeMigration);
                upgradeEvidence = new UpgradeEvidence(connection.getMetaData().getURL(), connection.getCatalog(),
                        beforeMigration, after);
                cleanup(connection);
                // DB側の既定値はEntityが明示する0で代用せず、callback内の省略INSERTで確かめる。
                withForeignKeysDisabled(connection, () -> {
                    for (String table : ENTITIES.keySet()) {
                        seed(connection, table, FIRST_ID, null);
                        assertThat(version(connection, table, FIRST_ID)).as("%sのversion省略時は0", table).isZero();
                    }
                });
                cleanup(connection);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("シフトversion既存行のmigration検証に失敗", e);
        }
    }

    void assertUpgradeVerified(Connection connection) throws SQLException {
        assertThat(upgradeEvidence).as("このcontainerの初回migration callback証拠がある").isNotNull();
        assertThat(upgradeEvidence.jdbcUrl()).isEqualTo(connection.getMetaData().getURL());
        assertThat(upgradeEvidence.catalog()).isEqualTo(connection.getCatalog());
        assertThat(upgradeEvidence.before()).containsOnlyKeys(ENTITIES.keySet());
        for (String table : ENTITIES.keySet()) {
            assertThat(upgradeEvidence.before().get(table)).as("%sの旧5境界を実投入した", table).hasSize(OLD_VERSIONS.size());
        }
        assertThat(upgradeEvidence.after()).isEqualTo(upgradeEvidence.before());
        assertColumnDefinitions(connection, "bigint");
    }

    static void assertDefinitionsAndConstraints(SessionFactory factory, Connection connection) throws SQLException {
        assertColumnDefinitions(connection, "bigint");
        for (String table : ENTITIES.keySet()) {
            long id = persist(factory, table, 0L);
            try {
                assertThat(version(connection, table, id)).as("新規Entityのversionは0").isZero();
                try (Statement statement = connection.createStatement()) {
                    assertThatThrownBy(() -> statement.executeUpdate("UPDATE " + table
                            + " SET version = -1 WHERE id = " + id))
                            .as("%sの非負制約", table).isInstanceOf(SQLException.class);
                    assertThatThrownBy(() -> statement.executeUpdate("UPDATE " + table
                            + " SET version = NULL WHERE id = " + id))
                            .as("%sのNOT NULL制約", table).isInstanceOf(SQLException.class);
                    assertThatThrownBy(() -> statement.executeUpdate("UPDATE " + table
                            + " SET version = 9223372036854775808 WHERE id = " + id))
                            .as("%sのsigned BIGINT上限", table).isInstanceOf(SQLException.class);
                }
                assertThat(version(connection, table, id)).isZero();
            } finally {
                cleanupRow(connection, table, id);
            }
        }
    }

    static void assertHibernateUpdates(SessionFactory factory, Connection connection) throws SQLException {
        for (Map.Entry<String, Class<?>> entry : ENTITIES.entrySet()) {
            for (Long original : UPDATE_VERSIONS) {
                long id = persist(factory, entry.getKey(), original);
                try (Session session = factory.openSession()) {
                    session.beginTransaction();
                    Object entity = session.find(entry.getValue(), id);
                    assertThat(entity).as("%s version=%sを読める", entry.getKey(), original).isNotNull();
                    assertThat(entityVersion(entity)).isEqualTo(original);
                    dirty(entity);
                    session.flush();
                    assertThat(entityVersion(entity)).isEqualTo(original + 1);
                    session.getTransaction().commit();
                    assertThat(version(connection, entry.getKey(), id)).as("%s version=%sの実増分", entry.getKey(), original)
                            .isEqualTo(original + 1);
                } finally {
                    cleanupRow(connection, entry.getKey(), id);
                }
            }
        }
    }

    static void assertOverflowRollsBack(SessionFactory factory, Connection connection) throws SQLException {
        for (Map.Entry<String, Class<?>> entry : ENTITIES.entrySet()) {
            long id = persist(factory, entry.getKey(), Long.MAX_VALUE);
            List<List<String>> before = snapshotRow(connection, entry.getKey(), id);
            try (Session session = factory.openSession()) {
                session.beginTransaction();
                Object entity = session.find(entry.getValue(), id);
                dirty(entity);
                // LongJavaType.nextは上限で負値へwrapするため、DBのCHECKで拒否して全更新を戻す。
                Throwable failure = org.assertj.core.api.Assertions.catchThrowable(session::flush);
                session.getTransaction().rollback();
                assertThat(failure).as("%s Long上限の増分をDBで拒否", entry.getKey())
                        .isInstanceOf(org.hibernate.JDBCException.class);
                SQLException sqlFailure = ((org.hibernate.JDBCException) failure).getSQLException();
                assertThat(sqlFailure.getErrorCode()).as("MySQL CHECK違反のvendor code").isEqualTo(3819);
                assertThat(sqlFailure.getSQLState()).as("MySQL CHECK違反のSQLState").isEqualTo("HY000");
                assertThat(sqlFailure.getMessage()).as("対象versionの非負制約で拒否")
                        .contains("chk_" + entry.getKey() + "_version_nonnegative");
                assertThat(snapshotRow(connection, entry.getKey(), id)).as("versionと業務全列がrollbackで保たれる")
                        .isEqualTo(before);
            } finally {
                cleanupRow(connection, entry.getKey(), id);
            }
        }
    }

    static void assertStaleWriterRejected(SessionFactory factory, Connection connection) throws SQLException {
        for (Map.Entry<String, Class<?>> entry : ENTITIES.entrySet()) {
            long id = persist(factory, entry.getKey(), 4_294_967_295L);
            try (Session first = factory.openSession(); Session stale = factory.openSession()) {
                first.beginTransaction();
                stale.beginTransaction();
                Object firstEntity = first.find(entry.getValue(), id);
                Object staleEntity = stale.find(entry.getValue(), id);
                dirty(firstEntity);
                first.flush();
                first.getTransaction().commit();
                List<List<String>> committed = snapshotRow(connection, entry.getKey(), id);

                dirty(staleEntity);
                assertThatThrownBy(stale::flush).as("%sの2番目のTXは古いversionで失敗", entry.getKey())
                        .isInstanceOf(OptimisticLockException.class);
                stale.getTransaction().rollback();
                assertThat(snapshotRow(connection, entry.getKey(), id)).as("最初のcommitをstale writerが上書きしない")
                        .isEqualTo(committed);
                assertThat(version(connection, entry.getKey(), id)).isEqualTo(4_294_967_296L);
            } finally {
                cleanupRow(connection, entry.getKey(), id);
            }
        }
    }

    /** 4 Entityの列型validateを実行する。versionに@JdbcTypeを付けた静的宣言だけでは代用できない。 */
    static SessionFactory sessionFactory(String jdbcUrl, String username, String password) {
        String url = jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "sessionVariables=FOREIGN_KEY_CHECKS=0";
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.DIALECT, MySQLDialect.class.getName())
                .applySetting(AvailableSettings.JAKARTA_JDBC_DRIVER, "com.mysql.cj.jdbc.Driver")
                .applySetting(AvailableSettings.JAKARTA_JDBC_URL, url)
                .applySetting(AvailableSettings.JAKARTA_JDBC_USER, username)
                .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, password)
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "validate")
                .build();
        try {
            MetadataSources sources = new MetadataSources(registry).addAnnotatedClass(BaseEntity.class);
            ENTITIES.values().forEach(sources::addAnnotatedClass);
            return sources.getMetadataBuilder()
                    .applyPhysicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy())
                    .applyImplicitNamingStrategy(new SpringImplicitNamingStrategy())
                    .build().buildSessionFactory();
        } catch (RuntimeException e) {
            StandardServiceRegistryBuilder.destroy(registry);
            throw e;
        }
    }

    private static void dirty(Object entity) {
        if (entity instanceof ShiftAssignmentEntity assignment) {
            assignment.confirm();
        } else if (entity instanceof ShiftAssignmentRunEntity run) {
            run.fail("version-test");
        } else if (entity instanceof ShiftChangeRequestEntity request) {
            request.withdraw();
        } else if (entity instanceof ShiftSwapRequestEntity swap) {
            swap.cancel();
        } else {
            throw new IllegalArgumentException("未対応のEntity: " + entity);
        }
    }

    private static Long entityVersion(Object entity) {
        if (entity instanceof ShiftAssignmentEntity assignment) {
            return assignment.getVersion();
        } else if (entity instanceof ShiftAssignmentRunEntity run) {
            return run.getVersion();
        } else if (entity instanceof ShiftChangeRequestEntity request) {
            return request.getVersion();
        } else if (entity instanceof ShiftSwapRequestEntity swap) {
            return swap.getVersion();
        }
        throw new IllegalArgumentException("未対応のEntity: " + entity);
    }

    /** 最終schemaのデータは現Entityから作る。JPAが返したID以外の行を後続試験で操作しない。 */
    private static long persist(SessionFactory factory, String table, long version) {
        Object entity = switch (table) {
            case "shift_assignments" -> ShiftAssignmentEntity.builder().slotId(FIRST_ID).userId(1L)
                    .assignedBy(1L).note("version-test").version(version).build();
            case "shift_assignment_runs" -> ShiftAssignmentRunEntity.builder().scheduleId(1L).triggeredBy(1L)
                    .version(version).build();
            case "shift_change_requests" -> ShiftChangeRequestEntity.builder().scheduleId(1L).requestedBy(1L)
                    .requestType(ChangeRequestType.PRE_CONFIRM_EDIT).version(version).build();
            case "shift_swap_requests" -> ShiftSwapRequestEntity.builder().slotId(FIRST_ID).requesterId(1L)
                    .version(version).build();
            default -> throw new IllegalArgumentException("未対応の表: " + table);
        };
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            session.persist(entity);
            session.getTransaction().commit();
            assertThat(entityVersion(entity)).as("persistで境界versionを切り詰めない").isEqualTo(version);
            return ((Number) session.getIdentifier(entity)).longValue();
        }
    }

    private static void assertColumnDefinitions(Connection connection, String expectedType) throws SQLException {
        for (String table : ENTITIES.keySet()) {
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
                    "SELECT COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "' AND COLUMN_NAME = 'version'")) {
                assertThat(result.next()).as("%s.versionが存在", table).isTrue();
                assertThat(result.getString(1)).isEqualTo(expectedType);
                assertThat(result.getString(2)).isEqualTo("NO");
                assertThat(result.getString(3)).isEqualTo("0");
            }
        }
    }

    private static void seed(Connection connection, String table, long id, Long version) throws SQLException {
        String columns;
        String values;
        switch (table) {
            case "shift_assignments" -> {
                columns = "slot_id,user_id,assigned_by,note";
                values = id + ",1,1,'old-version-row'";
            }
            case "shift_assignment_runs" -> {
                columns = "schedule_id,triggered_by,error_message";
                values = "1,1,'old-version-row'";
            }
            case "shift_change_requests" -> {
                columns = "schedule_id,requested_by,request_type,reason";
                values = "1,1,'PRE_CONFIRM_EDIT','old-version-row'";
            }
            case "shift_swap_requests" -> {
                columns = "slot_id,requester_id,reason";
                values = id + ",1,'old-version-row'";
            }
            default -> throw new IllegalArgumentException("未対応の表: " + table);
        }
        if (version != null) {
            columns += ",version";
            values += "," + version;
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO " + table + " (id," + columns + ") VALUES (" + id + "," + values + ")");
        }
    }

    private static long version(Connection connection, String table, long id) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
                "SELECT version FROM " + table + " WHERE id = " + id)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    /** 型拡張で業務列や日時まで変更しないことを、全列のJDBC生byte表現で比較する。 */
    private static Map<String, List<List<String>>> snapshot(Connection connection) throws SQLException {
        Map<String, List<List<String>>> snapshot = new LinkedHashMap<>();
        for (String table : ENTITIES.keySet()) {
            snapshot.put(table, snapshotRows(connection, table, "id BETWEEN " + FIRST_ID + " AND "
                    + (FIRST_ID + OLD_VERSIONS.size() - 1)));
        }
        return Collections.unmodifiableMap(snapshot);
    }

    private static List<List<String>> snapshotRow(Connection connection, String table, long id) throws SQLException {
        List<List<String>> rows = snapshotRows(connection, table, "id = " + id);
        assertThat(rows).as("%sの所有行%sを全列取得", table, id).hasSize(1);
        return rows;
    }

    private static List<List<String>> snapshotRows(Connection connection, String table, String predicate) throws SQLException {
        List<List<String>> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
                "SELECT * FROM " + table + " WHERE " + predicate + " ORDER BY id")) {
            while (result.next()) {
                List<String> row = new ArrayList<>();
                for (int index = 1; index <= result.getMetaData().getColumnCount(); index++) {
                    byte[] value = result.getBytes(index);
                    row.add(value == null ? null : Base64.getEncoder().encodeToString(value));
                }
                rows.add(Collections.unmodifiableList(row));
            }
        }
        return Collections.unmodifiableList(rows);
    }

    private static void cleanupRow(Connection connection, String table, long id) throws SQLException {
        withForeignKeysDisabled(connection, () -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM " + table + " WHERE id = " + id);
            }
        });
    }

    private static void cleanup(Connection connection) throws SQLException {
        withForeignKeysDisabled(connection, () -> {
            try (Statement statement = connection.createStatement()) {
                for (String table : ENTITIES.keySet()) {
                    statement.executeUpdate("DELETE FROM " + table + " WHERE id BETWEEN " + FIRST_ID
                            + " AND " + (FIRST_ID + OLD_VERSIONS.size() - 1));
                }
            }
        });
    }

    private static void withForeignKeysDisabled(Connection connection, SqlAction action) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
                "SELECT @@FOREIGN_KEY_CHECKS")) {
            result.next();
            int previous = result.getInt(1);
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            try {
                action.run();
            } finally {
                statement.execute("SET FOREIGN_KEY_CHECKS = " + previous);
            }
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }
}
