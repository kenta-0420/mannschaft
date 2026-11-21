package com.mannschaft.app.shiftbudget.closeperiod;

import com.mannschaft.app.shift.event.ShiftPublishedEvent;
import com.mannschaft.app.shiftbudget.service.ShiftRecordOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260909-2152 α Codex 再検分 P2: 同じ枠×人で内容の変わる再公開を3回以上繰り返しても、取消が
 * {@code uq_sbc_slot_user_status (slot_id, user_id, status, deleted_at_uq)} に違反せず、毎回の取消の履歴（CANCELLED 行・
 * 取消理由・金額・時給・時間）を消さずに残す（実DB）。
 *
 * <p>1回目の再公開で生存 CANCELLED が1行でき、2回目以降の取消が同キーの2行目の生存 CANCELLED を作ろうとして UNIQUE 違反で
 * TX がロールバックしていた。取消の前に、同キーの旧 CANCELLED を論理削除（deleted_at）して deleted_at_uq をずらす。</p>
 */
@DisplayName("CMP-260909-2152 α 再検分 P2 内容の変わる再公開の繰り返しで取消が UNIQUE に当たらず履歴を残す")
class ClosePeriodRepublishHistoryIT extends ClosePeriodTestBase {

    private ShiftRecordOutcome republish(long scheduleId) {
        jdbc.update("UPDATE shift_schedules SET status = 'PUBLISHED' WHERE id = ?", scheduleId);
        return recordListener.onShiftPublished(new ShiftPublishedEvent(scheduleId, teamId, adminId, LocalDateTime.now()));
    }

    private void change(long slotId, String rate, String end) {
        jdbc.update("UPDATE shift_hourly_rates SET hourly_rate = ? WHERE user_id = ? AND team_id = ?",
                new BigDecimal(rate), workerId, teamId);
        // 時刻は JPA と同じ経路で書く（素の JDBC 文字列は接続タイムゾーン変換の対象外で、読み戻しがずれる）
        tx.executeWithoutResult(s -> em.createQuery("UPDATE ShiftSlotEntity s SET s.endTime = :t WHERE s.id = :id")
                .setParameter("t", java.time.LocalTime.parse(end)).setParameter("id", slotId).executeUpdate());
    }

    private List<Map<String, Object>> rows(long slotId) {
        return jdbc.queryForList("SELECT status, hourly_rate_snapshot, hours, amount, cancel_reason, deleted_at "
                + "FROM shift_budget_consumptions WHERE slot_id = ? AND user_id = ? ORDER BY id", slotId, workerId);
    }

    @Test
    @DisplayName("1000円×4h → 2000円×2h → 1000円×4h → 1500円×8h と再公開し、最後に公開取消しても失敗せず、全取消履歴を残す")
    void 内容の変わる再公開を繰り返しても取消が失敗せず履歴を残す() {
        Long allocationId = allocation(d("2026-10-01"), d("2026-10-31"));
        long[] ids = shift(teamId, d("2026-10-10"), java.time.LocalTime.of(9, 0), java.time.LocalTime.of(13, 0), workerId);
        long scheduleId = ids[0];
        long slotId = ids[1];

        assertThat(republish(scheduleId).failed()).as("初回公開 1000円×4h").isZero();

        change(slotId, "2000", "11:00:00");
        assertThat(republish(scheduleId).failed()).as("再公開1 2000円×2h（合計同額）").isZero();
        change(slotId, "1000", "13:00:00");
        assertThat(republish(scheduleId).failed()).as("再公開2 1000円×4h（同キー2回目の取消）").isZero();
        change(slotId, "1500", "17:00:00");
        assertThat(republish(scheduleId).failed()).as("再公開3 1500円×8h（同キー3回目の取消）").isZero();

        // 生存 PLANNED は最後の内容だけ、割当の消化額も最後の内容だけ
        assertThat(liveStatus(slotId, workerId)).isEqualTo("PLANNED");
        Map<String, Object> live = jdbc.queryForMap("SELECT hourly_rate_snapshot, hours, amount FROM shift_budget_consumptions "
                + "WHERE slot_id = ? AND user_id = ? AND status = 'PLANNED' AND deleted_at IS NULL", slotId, workerId);
        assertThat((BigDecimal) live.get("hourly_rate_snapshot")).isEqualByComparingTo("1500");
        assertThat((BigDecimal) live.get("hours")).isEqualByComparingTo("8");
        assertThat((BigDecimal) live.get("amount")).isEqualByComparingTo("12000");
        assertThat(consumedAmount(allocationId)).isEqualByComparingTo("12000");

        // 公開取消（取消経路でも同キー4回目の取消が UNIQUE に当たらない）
        unpublish(scheduleId);
        assertThat(liveStatus(slotId, workerId)).isNull();
        assertThat(consumedAmount(allocationId)).isEqualByComparingTo("0");
        Long failed = jdbc.queryForObject("SELECT COUNT(*) FROM shift_budget_failed_events WHERE organization_id = ?",
                Long.class, orgId);
        assertThat(failed).as("記録・取消の失敗イベントが無い").isZero();

        // 履歴: 4回分の内容がすべて CANCELLED として残る（物理削除・上書きなし）。生存 CANCELLED は最新の1行のみ
        List<Map<String, Object>> all = rows(slotId);
        assertThat(all).hasSize(4);
        assertThat(all).allSatisfy(r -> assertThat(r.get("status")).isEqualTo("CANCELLED"));
        assertThat(all).extracting(r -> ((BigDecimal) r.get("hourly_rate_snapshot")).stripTrailingZeros().toPlainString())
                .containsExactly("1000", "2000", "1000", "1500");
        assertThat(all).extracting(r -> ((BigDecimal) r.get("hours")).stripTrailingZeros().toPlainString())
                .containsExactly("4", "2", "4", "8");
        assertThat(all).extracting(r -> String.valueOf(r.get("cancel_reason")))
                .containsExactly("RE_PUBLISHED", "RE_PUBLISHED", "RE_PUBLISHED", "SHIFT_DELETED");
        assertThat(all).filteredOn(r -> r.get("deleted_at") == null).hasSize(1);
        assertThat(all.get(3).get("deleted_at")).as("最新の取消は生存のまま").isNull();
    }
}
