package com.mannschaft.app.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * CMP-260909-2152 α（組織の締め日・締め期間）の migration の事前検査（Flyway の外・読み取りのみ。確定設計 r12 §8）。
 *
 * <p>{@code FlywayRepairConfig} の起動戦略が「事前検査 → repair() → migrate()」の順で呼ぶ。repair より前に検査して異常なら起動を止めるので、
 * スキーマ・データ・Flyway 履歴とも不変のまま停止できる。</p>
 *
 * <ul>
 *   <li>DB の状態の識別は information_schema と Flyway の履歴（info）だけで行う。データ検査は識別した状態に応じた業務表への SELECT のみ</li>
 *   <li>空 DB（履歴表なし、または shift_budget_consumptions なし）は検査を省略して migrate へ進む</li>
 *   <li>α が部分適用なら、<b>次に適用するファイルに必要な検査だけ</b>を行う（失敗行は未適用扱い）。全適用済みなら検査しない</li>
 *   <li>検査項目: 勤務日（work_date）を復元できない消化行（枠なし。backfill 前）／backfill 適用済みで NOT NULL 化が未適用のときの work_date NULL 行／生存旧月次仕訳の月末日以外・同割当同月2件以上・割当なし／
 *       旧 #4560 の V261 表の存在（作り直しの案内）</li>
 * </ul>
 *
 * <p>migration 内の一時表＋CHECK の防御は別に残る（この検査を通らない CLI／Gradle 直実行でも推測で取り込まない）。α の migration は
 * 説明に {@value #ALPHA_MARKER} を含む締め期間表の作成ファイルの major で識別する（採番は並行 PR の着地順で変わるため固定しない）。</p>
 */
final class ShiftBudgetClosePeriodPreflight {

    /** α の識別子。締め期間表を作る migration の説明に含まれる。 */
    static final String ALPHA_MARKER = "shift_budget_close_periods";

    private static final List<String> ALPHA_TABLES = List.of("shift_budget_closing_settings",
            "shift_budget_closing_day_changes", "shift_budget_close_periods", "shift_budget_monthly_close_events",
            "shift_budget_record_deferrals", "budget_shift_close_postings");

    private static final int LIST_LIMIT = 30;

    private ShiftBudgetClosePeriodPreflight() {
    }

    /** 異常があれば {@link IllegalStateException}（異常行 ID を含む）。 */
    static void check(Flyway flyway) {
        MigrationInfo[] all = flyway.info().all();
        Optional<String> alphaMajor = Arrays.stream(all)
                .filter(m -> m.getVersion() != null && normalized(m).contains(ALPHA_MARKER))
                .map(ShiftBudgetClosePeriodPreflight::major)
                .findFirst();
        if (alphaMajor.isEmpty()) {
            return;
        }
        List<MigrationInfo> alpha = Arrays.stream(all)
                .filter(m -> m.getVersion() != null && major(m).equals(alphaMajor.get()))
                .toList();
        List<MigrationInfo> pending = alpha.stream().filter(m -> !m.getState().isApplied()).toList();
        if (pending.isEmpty()) {
            return;
        }
        DataSource dataSource = flyway.getConfiguration().getDataSource();
        if (dataSource == null) {
            return;
        }
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            if (!tableExists(st, "flyway_schema_history") || !tableExists(st, "shift_budget_consumptions")) {
                return; // 空 DB は検査を省略して migrate へ
            }
            List<String> problems = new ArrayList<>();
            checkOldV261Tables(st, pending, problems);
            if (isPending(pending, "backfill work date")) {
                checkConsumptionsWithoutSlot(st, problems);
            }
            // NULL は backfill 前なら正常な中間状態（列追加まで適用して停止した DB）。backfill 適用済みで NOT NULL 化が未適用のときだけ検査する
            if (isPending(pending, "work date not null") && !isPending(pending, "backfill work date")
                    && columnExists(st, "shift_budget_consumptions", "work_date")) {
                checkNullWorkDate(st, problems);
            }
            if (isPending(pending, "import legacy monthly close") && tableExists(st, "budget_transactions")) {
                checkLegacyJournals(st, problems);
            }
            if (!problems.isEmpty()) {
                throw new IllegalStateException("CMP-260909-2152 α migration の事前検査に失敗しました（スキーマ・データ・履歴は変更していません）。"
                        + "異常を解消してから再起動してください:\n- " + String.join("\n- ", problems));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("CMP-260909-2152 α migration の事前検査で DB を読めませんでした", e);
        }
    }

    // ───────────────────────── 検査 ─────────────────────────

    /** 旧 #4560 の V261 で作られた表が、α の作成ファイルの未適用のまま残っていないか（作り直しの案内）。 */
    private static void checkOldV261Tables(Statement st, List<MigrationInfo> pending, List<String> problems) throws SQLException {
        for (String table : ALPHA_TABLES) {
            String needle = "create " + table.replace('_', ' ');
            if (isPending(pending, needle) && tableExists(st, table)) {
                problems.add("表 " + table + " が既に存在しますが、α の作成 migration は未適用です（旧 #4560 の V261 適用済みの開発DBは作り直してください。"
                        + "手順: docs/operations/f087_monthly_close_recovery.md）");
            }
        }
    }

    /** work_date を復元できない（勤務枠が無い）消化行。 */
    private static void checkConsumptionsWithoutSlot(Statement st, List<String> problems) throws SQLException {
        List<Long> ids = ids(st, "SELECT c.id FROM shift_budget_consumptions c LEFT JOIN shift_slots s ON s.id = c.slot_id "
                + "WHERE s.id IS NULL ORDER BY c.id LIMIT " + LIST_LIMIT);
        if (!ids.isEmpty()) {
            problems.add("勤務枠が無く work_date を復元できない消化行（shift_budget_consumptions.id）: " + ids
                    + "。推測値は入れません。開発DBは該当行を削除するか DB を作り直してください");
        }
    }

    /** NOT NULL 化の前に work_date が NULL の行が無いこと。 */
    private static void checkNullWorkDate(Statement st, List<String> problems) throws SQLException {
        List<Long> ids = ids(st, "SELECT id FROM shift_budget_consumptions WHERE work_date IS NULL ORDER BY id LIMIT " + LIST_LIMIT);
        if (!ids.isEmpty()) {
            problems.add("work_date が NULL の消化行（shift_budget_consumptions.id）: " + ids);
        }
    }

    /** 生存旧月次仕訳（deleted_at IS NULL）の月末日以外・同割当同月2件以上・割当なし。 */
    private static void checkLegacyJournals(Statement st, List<String> problems) throws SQLException {
        List<Long> notMonthEnd = ids(st, "SELECT t.id FROM budget_transactions t WHERE t.source_type = 'SHIFT_BUDGET_MONTHLY' "
                + "AND t.deleted_at IS NULL AND t.transaction_date <> LAST_DAY(t.transaction_date) ORDER BY t.id LIMIT " + LIST_LIMIT);
        if (!notMonthEnd.isEmpty()) {
            problems.add("月末日以外の日付の生存旧月次仕訳（budget_transactions.id）: " + notMonthEnd);
        }
        List<Long> duplicated = ids(st, "SELECT t.id FROM budget_transactions t JOIN ("
                + "SELECT source_id, DATE_FORMAT(transaction_date, '%Y-%m') AS ym FROM budget_transactions "
                + "WHERE source_type = 'SHIFT_BUDGET_MONTHLY' AND deleted_at IS NULL "
                + "GROUP BY source_id, DATE_FORMAT(transaction_date, '%Y-%m') HAVING COUNT(*) > 1) d "
                + "ON d.source_id = t.source_id AND d.ym = DATE_FORMAT(t.transaction_date, '%Y-%m') "
                + "WHERE t.source_type = 'SHIFT_BUDGET_MONTHLY' AND t.deleted_at IS NULL ORDER BY t.id LIMIT " + LIST_LIMIT);
        if (!duplicated.isEmpty()) {
            problems.add("同じ割当・同じ月に2件以上ある生存旧月次仕訳（budget_transactions.id）: " + duplicated);
        }
        if (tableExists(st, "shift_budget_allocations")) {
            List<Long> noAllocation = ids(st, "SELECT t.id FROM budget_transactions t LEFT JOIN shift_budget_allocations a "
                    + "ON a.id = t.source_id WHERE t.source_type = 'SHIFT_BUDGET_MONTHLY' AND t.deleted_at IS NULL "
                    + "AND a.id IS NULL ORDER BY t.id LIMIT " + LIST_LIMIT);
            if (!noAllocation.isEmpty()) {
                problems.add("割当が存在しない生存旧月次仕訳（budget_transactions.id）: " + noAllocation);
            }
        }
    }

    // ───────────────────────── 補助 ─────────────────────────

    private static boolean isPending(List<MigrationInfo> pending, String descriptionFragment) {
        return pending.stream().anyMatch(m -> m.getDescription().toLowerCase().replace('_', ' ').contains(descriptionFragment));
    }

    private static String normalized(MigrationInfo m) {
        return m.getDescription().replace(' ', '_').toLowerCase();
    }

    private static String major(MigrationInfo m) {
        String v = m.getVersion().getVersion();
        int dot = v.indexOf('.');
        return dot < 0 ? v : v.substring(0, dot);
    }

    private static boolean tableExists(Statement st, String table) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = '" + table + "'")) {
            return rs.next() && rs.getLong(1) > 0;
        }
    }

    private static boolean columnExists(Statement st, String table, String column) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = '" + table + "' AND COLUMN_NAME = '" + column + "'")) {
            return rs.next() && rs.getLong(1) > 0;
        }
    }

    private static List<Long> ids(Statement st, String sql) throws SQLException {
        List<Long> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getLong(1));
            }
        }
        return out;
    }
}
