package com.mannschaft.app.common.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * AC18の旧F18残高互換。既存FromScratchコンテナの専用clone schemaへ実migrationを適用する。
 * <p>呼出側はCREATE/DROP SCHEMA権限を持つ専用autocommit接続を渡す。元schemaを変更しない。
 * 旧2表にはカード凍結列が無いため、存在しない状態を捏造せずNULL/0/正残高と全操作種別を残す。</p>
 */
final class LoyaltyLegacyMigrationFixture {
    private static final String CARDS = "user_point_cards";
    private static final String EVENTS = "point_card_balance_events";
    private static final String AT = "2020-01-02 03:04:05.123456";
    private LoyaltyLegacyMigrationFixture() { }

    static void verify(Connection connection) throws SQLException {
        String originalCatalog = connection.getCatalog();
        boolean originalAutoCommit = connection.getAutoCommit();
        assertThat(originalCatalog).as("既存FromScratch schemaを持つ専用接続").isNotBlank();
        // MySQL DDLは暗黙commitを行う。呼出側の未確定TXをcommitする接続は受けない。
        if (!originalAutoCommit) throw new SQLException("専用autocommit接続を渡してください");
        String clone = "loyalty_legacy_" + UUID.randomUUID().toString().replace("-", "");
        boolean created = false;
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + identifier(clone)
                    + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
            connection.setCatalog(clone);
            for (String table : List.of(CARDS, EVENTS)) {
                statement.execute("CREATE TABLE " + identifier(clone) + "." + identifier(table) + " LIKE "
                        + identifier(originalCatalog) + "." + identifier(table));
            }
            seed(connection);
            Snapshot cards = snapshot(connection, CARDS);
            Snapshot events = snapshot(connection, EVENTS);
            assertThat(cards.count()).isEqualTo(3L);
            assertThat(events.count()).isEqualTo(5L);
            try {
                var resources = new PathMatchingResourcePatternResolver()
                        .getResources("classpath:db/migration/V*__create_team_loyalty_tables.sql");
                assertThat(resources).as("major/timestamp変更後も実6表migrationを一つだけ選ぶ").hasSize(1);
                ScriptUtils.executeSqlScript(connection, new EncodedResource(resources[0], StandardCharsets.UTF_8));
            } catch (IOException exception) {
                throw new SQLException("実migration資源を読めません", exception);
            }
            assertThat(snapshot(connection, CARDS)).as("旧カードの全列hash/件数を維持").isEqualTo(cards);
            assertThat(snapshot(connection, EVENTS)).as("旧残高履歴の全列hash/件数を維持").isEqualTo(events);
            try (var tables = connection.getMetaData().getTables(clone, null, "team_loyalty_%", new String[]{"TABLE"})) {
                int count = 0; while (tables.next()) count++;
                assertThat(count).as("cloneに実migrationの新規6表が作られる").isEqualTo(6);
            }
        } finally {
            try { connection.setCatalog(originalCatalog); }
            finally {
                try {
                    // CREATE成功を観測した自分のUUID schemaだけを落とす。IF NOT EXISTSで既存資産を流用しない。
                    if (created) try (var cleanup = connection.createStatement()) {
                        cleanup.execute("DROP SCHEMA " + identifier(clone));
                    }
                } finally { connection.setAutoCommit(originalAutoCommit); }
            }
        }
    }

    private static void seed(Connection connection) throws SQLException {
        String external = UUID.randomUUID().toString();
        String zero = UUID.randomUUID().toString();
        String positive = UUID.randomUUID().toString();
        String provider = UUID.randomUUID().toString();
        card(connection, external, null, null, null, false, 0);
        card(connection, zero, provider, BigDecimal.ZERO, 0, true, 1);
        card(connection, positive, provider, new BigDecimal("1250.75"), 7, false, 2);
        event(connection, zero, provider, "CHARGE", "1.00", "1.00", null, null);
        event(connection, zero, provider, "SPENT", "-1.00", "0.00", null, "残高ゼロ");
        event(connection, positive, provider, "CHARGE", "2500.75", "2500.75", null, "旧入金");
        String spent = event(connection, positive, provider, "SPENT", "-1500.25", "1000.50", null, null);
        event(connection, positive, provider, "REFUND", "250.25", "1250.75", spent, "一部返金");
    }

    private static void card(Connection connection, String id, String provider, BigDecimal balance,
            Integer stamps, boolean favorite, int order) throws SQLException {
        String sql = "INSERT INTO user_point_cards(id,user_id,provider_id,display_name,nickname,barcode_value,"
                + "barcode_format,last4,memo,is_favorite,display_order,balance,stamp_count,last_used_at,created_at,updated_at) "
                + "VALUES (?,?,?,?,?,?, 'CODE128',?,?,?,?,?,?, ?,?,?)";
        try (var insert = connection.prepareStatement(sql)) {
            insert.setString(1,id); insert.setLong(2,8_700_000_001L); insert.setString(3,provider);
            // 暗号化カラムはopaque bytesとして保持を検証し、復号や実利用者情報を持ち込まない。
            insert.setBytes(4,new byte[]{0,1,2,(byte)255});
            if (provider == null) insert.setNull(5,Types.VARBINARY); else insert.setBytes(5,new byte[]{3,0,4});
            insert.setBytes(6,new byte[]{5,6,0,(byte)254}); insert.setString(7,"1234");
            insert.setBytes(8,new byte[]{7,0,8}); insert.setBoolean(9,favorite); insert.setInt(10,order);
            insert.setBigDecimal(11,balance);
            if (stamps == null) insert.setNull(12,Types.INTEGER); else insert.setInt(12,stamps);
            insert.setString(13,provider == null ? null : AT); insert.setString(14,AT); insert.setString(15,AT);
            insert.executeUpdate();
        }
    }

    private static String event(Connection connection, String card, String provider, String operation,
            String delta, String balance, String refund, String note) throws SQLException {
        String id = UUID.randomUUID().toString();
        try (var insert = connection.prepareStatement("INSERT INTO point_card_balance_events "
                + "(id,card_id,provider_id,organization_id,operation_type,delta,balance_after,refund_of_event_id,"
                + "operated_by_user_id,operated_at,note,created_at) VALUES (?,?,?,8700000002,?,?,?,?,8700000003,?,?,?)")) {
            insert.setString(1,id); insert.setString(2,card); insert.setString(3,provider); insert.setString(4,operation);
            insert.setBigDecimal(5,new BigDecimal(delta)); insert.setBigDecimal(6,new BigDecimal(balance));
            insert.setString(7,refund); insert.setString(8,AT); insert.setString(9,note); insert.setString(10,AT);
            insert.executeUpdate();
        }
        return id;
    }

    private static Snapshot snapshot(Connection connection, String table) throws SQLException {
        var hashes = new ArrayList<String>();
        try (var select = connection.createStatement(); var rows = select.executeQuery("SELECT * FROM " + identifier(table) + " ORDER BY id")) {
            var metadata = rows.getMetaData();
            while (rows.next()) {
                MessageDigest hash = sha256();
                for (int column = 1; column <= metadata.getColumnCount(); column++) {
                    hash.update(ByteBuffer.allocate(4).putInt(metadata.getColumnType(column)).array());
                    field(hash,metadata.getColumnName(column).getBytes(StandardCharsets.UTF_8));
                    field(hash,rows.getBytes(column));
                }
                hashes.add(HexFormat.of().formatHex(hash.digest()));
            }
        }
        long count;
        try (var select = connection.createStatement(); var row = select.executeQuery("SELECT COUNT(*) FROM " + identifier(table))) {
            assertThat(row.next()).isTrue(); count = row.getLong(1);
        }
        assertThat(hashes).hasSize(Math.toIntExact(count));
        return new Snapshot(count,List.copyOf(hashes));
    }

    private static void field(MessageDigest hash, byte[] value) {
        hash.update((byte)(value == null ? 0 : 1));
        if (value != null) { hash.update(ByteBuffer.allocate(4).putInt(value.length).array()); hash.update(value); }
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256を使用できません",exception); }
    }
    private static String identifier(String value) { return "`" + value.replace("`","``") + "`"; }
    private record Snapshot(long count, List<String> hashes) { }
}
