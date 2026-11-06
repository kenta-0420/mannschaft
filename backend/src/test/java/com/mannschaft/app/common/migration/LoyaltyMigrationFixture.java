package com.mannschaft.app.common.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 実DDLのCHECK・複合FK・生成列一意性を直接確認する。専用container内で全fixtureをrollbackする。 */
final class LoyaltyMigrationFixture {
    private LoyaltyMigrationFixture() { }

    static void verify(Connection connection) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement sql = connection.createStatement()) {
            String rule = binaryUuid();
            String foreignRule = binaryUuid();
            String account = binaryUuid();
            String team = "7800000001";
            sql.executeUpdate("INSERT INTO team_loyalty_rules(id,team_id,name,points,frequency,is_self_award_enabled,is_active,version,created_at,updated_at) VALUES ("
                    + rule + "," + team + ",'受付',1,'ONCE_PER_JST_DAY',TRUE,TRUE,0,NOW(),NOW()),("
                    + foreignRule + ",7800000002,'別TEAM',1,'ONCE_PER_JST_DAY',FALSE,TRUE,0,NOW(),NOW())");
            sql.executeUpdate("INSERT INTO team_loyalty_accounts(id,team_id,user_id,balance,lifetime_earned,created_at,updated_at) VALUES ("
                    + account + "," + team + ",7800000003,0,0,NOW(),NOW())");
            rejects(sql, "UPDATE team_loyalty_accounts SET balance=-1 WHERE id=" + account, 3819);
            rejects(sql, "UPDATE team_loyalty_rules SET frequency='EACH_STAFF_CONFIRMATION' WHERE id=" + rule, 3819);
            String transaction = "INSERT INTO team_loyalty_transactions(id,team_id,account_id,user_id,actor_user_id,kind,channel,request_operation,delta,balance_after,rule_id,label,rule_or_reward_version,program_version,request_key,payload_hash,created_at,updated_at) VALUES ("
                    + binaryUuid() + "," + team + "," + account + ",7800000003,7800000004,'AWARD','STAFF','STAFF_AWARD',1,1,"
                    + foreignRule + ",'他TEAM接続拒否',0,0," + binaryUuid() + ",UNHEX(REPEAT('00',32)),NOW(),NOW())";
            rejects(sql, transaction, 1452);
            String code = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase()
                    .replaceAll("[0189]", "A");
            String submitKey = binaryUuid();
            String request = "INSERT INTO team_loyalty_award_requests(id,team_id,user_id,rule_id,source_location_id,program_version,rule_version,points,rule_name,location_name,award_on,expires_at,status,version,verification_code,submit_key,submit_payload_hash,created_at,updated_at) VALUES ("
                    + binaryUuid() + "," + team + ",7800000003," + rule
                    + ",7800000005,0,0,1,'受付','拠点','2026-10-16','2026-10-16 12:30:00','PENDING',0,'"
                    + code + "'," + submitKey + ",UNHEX(REPEAT('00',32)),'2026-10-16 12:00:00','2026-10-16 12:00:00')";
            sql.executeUpdate(request);
            rejects(sql, request.replaceFirst("VALUES \\([^,]+", "VALUES (" + binaryUuid())
                    .replace("'" + code + "'", "'BBBBBBBB'").replace(submitKey, binaryUuid()), 1062);
            // NULLとの比較がUNKNOWNでもCHECKを通してはならない。
            rejects(sql, "UPDATE team_loyalty_award_requests SET status='CANCELLED',closed_at='2026-10-16 12:01:00',reason='USER_CANCELLED',terminal_action='CANCEL',action_key="
                    + binaryUuid() + ",action_payload_hash=UNHEX(REPEAT('00',32)) WHERE team_id=" + team, 3819);
            rejects(sql, "UPDATE team_loyalty_award_requests SET verification_code='abcdefgh' WHERE team_id=" + team, 3819);
        } finally {
            connection.rollback();
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    private static String binaryUuid() {
        return "UUID_TO_BIN('" + UUID.randomUUID() + "')";
    }

    private static void rejects(Statement sql, String statement, int vendorCode) {
        assertThatThrownBy(() -> sql.executeUpdate(statement)).isInstanceOf(SQLException.class)
                .extracting(error -> ((SQLException) error).getErrorCode()).isEqualTo(vendorCode);
    }
}
