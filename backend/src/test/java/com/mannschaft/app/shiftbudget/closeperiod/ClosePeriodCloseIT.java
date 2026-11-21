package com.mannschaft.app.shiftbudget.closeperiod;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.budget.dto.ShiftBudgetMonthlyPostCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260909-2152 PR-α 締め期間による締め（実DB・実HTTP）。
 *
 * <p>試練101（AC23）・102（AC24）・103（AC25）・107（AC29）・112（AC35）・113（AC36）・114a（AC37 締め側）・
 * 115（AC38）・117（AC40）・119（AC42）・149（AC30α）。</p>
 *
 * <p>実装前の red 理由: 締め日設定 API が無く（404）、手動締め応答に period_* が無く、締めイベント・期間表が無い
 * （SQL 文法エラー）。現行の締めは「対象暦月に完全包含される割当」だけを全 PLANNED まとめて締めるため、
 * 期間外 PLANNED が残る・仕訳日が締め日になる等の期待にも反する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260909-2152 α 締め期間による締め（実DB・実HTTP）")
class ClosePeriodCloseIT extends ClosePeriodTestBase {

    /** 15日締めを 2026-03 から適用する（2026-04＝3/16〜4/15、2026-05＝4/16〜5/15）。 */
    private void fifteenthClosing() throws Exception {
        today(d("2026-02-01"));
        setClosingDay(15, "2026-03");
        today(DEFAULT_TODAY);
    }

    @Test
    @DisplayName("試練101 AC23 設定なし＝末日締め。単月割当の締め結果（確定・加算・仕訳1件・仕訳日）は従来と同一で、応答に期間が付く")
    void 試練101_設定なしは末日締めで従来と同一() throws Exception {
        Long allocationId = allocation(d("2026-06-01"), d("2026-06-30"));
        long[] a = worked(d("2026-06-05"), workerId);
        long[] b = worked(d("2026-06-20"), workerId);

        JsonNode res = data(close("2026-06").andExpect(status().isOk()));
        assertThat(res.path("period_start").asText()).isEqualTo("2026-06-01");
        assertThat(res.path("period_end").asText()).isEqualTo("2026-06-30");
        assertThat(res.path("closing_day").isInt()).as("closing_day 欄").isTrue();
        assertThat(res.path("closing_day").asInt()).isZero();
        assertThat(res.path("closed_consumptions").asInt()).isEqualTo(2);

        assertThat(liveStatus(a[1], workerId)).isEqualTo("CONFIRMED");
        assertThat(liveStatus(b[1], workerId)).isEqualTo("CONFIRMED");
        assertThat(confirmedAmount(allocationId)).isEqualByComparingTo("16000");
        List<Map<String, Object>> j = journals(allocationId);
        assertThat(j).hasSize(1);
        assertThat((BigDecimal) j.get(0).get("amount")).isEqualByComparingTo("16000");
        assertThat(j.get(0).get("transaction_date").toString()).isEqualTo("2026-06-30");
        List<Map<String, Object>> e = closedEvents(allocationId);
        assertThat(e).hasSize(1);
        assertThat(e.get(0).get("target_month")).isEqualTo("2026-06");
        assertThat(((Number) e.get(0).get("line_count")).intValue()).isEqualTo(2);
        assertThat(e.get(0).get("journal_date").toString()).isEqualTo("2026-06-30");
    }

    @Test
    @DisplayName("試練102 AC24 15日締め 2026-05＝4/16〜5/15、仕訳日＝min(締め日, 割当終了日)=5/10、題名は期間表記")
    void 試練102_期間と仕訳日と題名() throws Exception {
        fifteenthClosing();
        Long allocationId = allocation(d("2026-04-16"), d("2026-05-10"));
        worked(d("2026-04-20"), workerId);
        worked(d("2026-05-08"), workerId);

        JsonNode res = data(close("2026-05").andExpect(status().isOk()));
        assertThat(res.path("period_start").asText()).isEqualTo("2026-04-16");
        assertThat(res.path("period_end").asText()).isEqualTo("2026-05-15");
        assertThat(res.path("closing_day").asInt()).isEqualTo(15);

        List<Map<String, Object>> j = journals(allocationId);
        assertThat(j).hasSize(1);
        assertThat(j.get(0).get("transaction_date").toString()).as("仕訳日＝割当終了日").isEqualTo("2026-05-10");
        assertThat((BigDecimal) j.get(0).get("amount")).isEqualByComparingTo("16000");
        String title = (String) j.get(0).get("title");
        assertThat(title).as("題名は暦月表記（旧: 2026年4月）ではなく期間表記").doesNotContain("2026年4月 ");
        assertThat(mentionsDate(title, d("2026-04-16"))).as("題名に期間開始 4/16: %s", title).isTrue();
        assertThat(mentionsDate(title, d("2026-05-15")) || mentionsDate(title, d("2026-05-10")))
                .as("題名に期間終了: %s", title).isTrue();
    }

    /** 題名の日付表記は ISO・M/d・M月d日 のいずれも受ける。 */
    private static boolean mentionsDate(String title, LocalDate date) {
        return title.contains(date.toString())
                || title.contains(date.getMonthValue() + "/" + date.getDayOfMonth())
                || title.contains(date.getMonthValue() + "月" + date.getDayOfMonth() + "日");
    }

    @Test
    @DisplayName("試練103 AC25 期間と重なる割当がすべて締まり、期間外の PLANNED は残る（期間外の割当は不変）")
    void 試練103_重なる割当がすべて締まる() throws Exception {
        fifteenthClosing();
        Long team2 = newTeamInOrg();
        Long team3 = newTeamInOrg();
        Long worker2 = worker(team2, orgId);
        Long worker3 = worker(team3, orgId);
        Long a = allocation(d("2026-04-16"), d("2026-06-30"));
        Long b = allocation(orgId, team2, fiscalYearId, categoryId, d("2026-05-01"), d("2026-05-31"));
        Long c = allocation(orgId, team3, fiscalYearId, categoryId, d("2026-05-16"), d("2026-06-30"));
        long[] a420 = worked(d("2026-04-20"), workerId);
        long[] a520 = worked(d("2026-05-20"), workerId);
        long[] b510 = worked(team2, d("2026-05-10"), worker2);
        long[] c520 = worked(team3, d("2026-05-20"), worker3);

        close("2026-05").andExpect(status().isOk());

        assertThat(liveStatus(a420[1], workerId)).isEqualTo("CONFIRMED");
        assertThat(liveStatus(a520[1], workerId)).as("期間外（2026-06）の PLANNED は残る").isEqualTo("PLANNED");
        assertThat(liveStatus(b510[1], worker2)).as("月の途中から始まる割当も締まる").isEqualTo("CONFIRMED");
        assertThat(liveStatus(c520[1], worker3)).as("期間と重ならない割当は締めない").isEqualTo("PLANNED");
        assertThat(confirmedAmount(a)).isEqualByComparingTo("8000");
        assertThat(consumedAmount(a)).isEqualByComparingTo("16000");
        assertThat(journals(a)).hasSize(1);
        assertThat(journals(a).get(0).get("transaction_date").toString()).isEqualTo("2026-05-15");
        assertThat(journals(b)).hasSize(1);
        assertThat(journals(b).get(0).get("transaction_date").toString()).isEqualTo("2026-05-15");
        assertThat(journals(c)).isEmpty();
        assertThat(closeEvents(c)).isEmpty();
    }

    @Test
    @DisplayName("試練107 AC29 締め済み期間の勤務は再公開で PLANNED を作らず、未計上候補に出る（締め済み行は CONFIRMED のまま）")
    void 試練107_締め済み期間の再公開はPLANNEDを作らない() throws Exception {
        Long allocationId = allocation(d("2026-04-01"), d("2026-05-31"));
        Long worker2 = worker(teamId, orgId);
        long[] s = worked(d("2026-04-10"), workerId);
        close("2026-04").andExpect(status().isOk());
        BigDecimal consumedAfterClose = consumedAmount(allocationId);

        assign(s[1], workerId, worker2);
        publish(s[0]);

        assertThat(liveStatus(s[1], workerId)).isEqualTo("CONFIRMED");
        assertThat(liveStatus(s[1], worker2)).as("締め済み期間に新 PLANNED を作らない").isNull();
        assertThat(consumedAmount(allocationId)).isEqualByComparingTo(consumedAfterClose);
        JsonNode items = data(uncounted(adminId, allocationId, "2026-04").andExpect(status().isOk())).path("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).path("user_id").asLong()).isEqualTo(worker2);
        assertThat(items.get(0).path("slot_id").asLong()).isEqualTo(s[1]);
        assertThat(items.get(0).path("work_date").asText()).isEqualTo("2026-04-10");
    }

    @Test
    @DisplayName("試練112 AC35 深夜跨ぎ勤務（4/15 22:00〜翌6:00）は開始日基準で 2026-04（〜4/15）に属する")
    void 試練112_深夜跨ぎは開始日基準() throws Exception {
        fifteenthClosing();
        Long allocationId = allocation(d("2026-04-01"), d("2026-05-31"));
        long[] night = shift(teamId, d("2026-04-15"), LocalTime.of(22, 0), LocalTime.of(6, 0), workerId);
        publish(night[0]);

        close("2026-04").andExpect(status().isOk());

        assertThat(liveStatus(night[1], workerId)).isEqualTo("CONFIRMED");
        List<Map<String, Object>> e = closedEvents(allocationId);
        assertThat(e).hasSize(1);
        assertThat(e.get(0).get("target_month")).isEqualTo("2026-04");
        assertThat(journals(allocationId).get(0).get("transaction_date").toString()).isEqualTo("2026-04-15");
    }

    @Test
    @DisplayName("試練113 AC36 年度境界: 2025年度の割当（3/1〜4/30）の 2026-04 期間（3/16〜4/15）は仕訳日 4/15・年度は割当の年度")
    void 試練113_年度境界と仕訳日() throws Exception {
        Long fy2025 = tx.execute(s -> insertFiscalYear(orgId, "2025-04-01", "2026-03-31", "OPEN"));
        Long cat2025 = tx.execute(s -> insertCategory(fy2025));
        today(d("2026-02-01"));
        setClosingDay(15, "2026-03");
        today(DEFAULT_TODAY);
        Long allocationId = allocation(orgId, teamId, fy2025, cat2025, d("2026-03-01"), d("2026-04-30"));
        worked(d("2026-03-10"), workerId);
        worked(d("2026-03-20"), workerId);

        close("2026-04").andExpect(status().isOk());

        List<Map<String, Object>> j = journals(allocationId);
        assertThat(j).as("追いつき締めで 2026-03（3/1〜3/15）と 2026-04 の2件").hasSize(2);
        assertThat(j.get(0).get("transaction_date").toString()).isEqualTo("2026-03-15");
        assertThat(j.get(1).get("transaction_date").toString()).isEqualTo("2026-04-15");
        assertThat(((Number) j.get(1).get("fiscal_year_id")).longValue()).as("年度は割当 fiscal_year_id が正本")
                .isEqualTo(fy2025);
    }

    @Test
    @DisplayName("試練114a AC37 終了日前日までの手動締めは 409 SHIFT_BUDGET_036（変更0）、終了日当日と翌日以降は締められる")
    void 試練114a_未終了期間の締め拒否() throws Exception {
        fifteenthClosing();
        Long allocationId = allocation(d("2026-04-16"), d("2026-05-31"));
        long[] s = worked(d("2026-05-01"), workerId);

        today(d("2026-05-14"));
        close("2026-05").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SHIFT_BUDGET_036"));
        assertThat(liveStatus(s[1], workerId)).isEqualTo("PLANNED");
        assertThat(journals(allocationId)).isEmpty();

        today(d("2026-05-15"));
        close("2026-05").andExpect(status().isOk());
        assertThat(liveStatus(s[1], workerId)).isEqualTo("CONFIRMED");
        assertThat(journals(allocationId)).hasSize(1);
    }

    @Test
    @DisplayName("試練114b AC37 終了判定の通過後に締め日変更（15日→28日）が割り込み、確定した期間が未終了になった場合は 409 SHIFT_BUDGET_036（締め0）")
    void 試練114b_終了判定後の締め日変更割り込み() throws Exception {
        fifteenthClosing();
        Long allocationId = allocation(d("2026-04-16"), d("2026-05-31"));
        long[] s = worked(d("2026-05-01"), workerId);
        today(d("2026-05-15"));
        java.util.concurrent.atomic.AtomicInteger interrupted = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(inv -> {
            if (interrupted.getAndIncrement() == 0) {
                setClosingDay(28, "2026-05");
            }
            return null;
        }).when(hooks).beforeEnsureFixed(org.mockito.ArgumentMatchers.anyLong(), any());

        close("2026-05").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SHIFT_BUDGET_036"));

        assertThat(interrupted.get()).as("割り込みフックが呼ばれた").isPositive();
        assertThat(liveStatus(s[1], workerId)).isEqualTo("PLANNED");
        assertThat(closeEvents(allocationId)).isEmpty();
        assertThat(journals(allocationId)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shift_budget_close_periods WHERE organization_id = ?",
                Long.class, orgId)).as("未終了の期間を確定しない").isZero();
    }

    @Test
    @DisplayName("試練114a AC37 未来の期間キー（2026-12）も 409 SHIFT_BUDGET_036")
    void 試練114a_未来キー() throws Exception {
        allocation(d("2026-12-01"), d("2026-12-31"));
        close("2026-12").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SHIFT_BUDGET_036"));
    }

    @Test
    @DisplayName("試練115 AC38 期間との重なり1日・勤務0件の割当も締まり、0円仕訳（仕訳日 5/15）とイベント（0件）が残る")
    void 試練115_重なり1日で勤務0件() throws Exception {
        fifteenthClosing();
        Long allocationId = allocation(d("2026-05-15"), d("2026-06-30"));

        close("2026-05").andExpect(status().isOk());

        List<Map<String, Object>> j = journals(allocationId);
        assertThat(j).hasSize(1);
        assertThat((BigDecimal) j.get(0).get("amount")).isEqualByComparingTo("0");
        assertThat(j.get(0).get("transaction_date").toString()).isEqualTo("2026-05-15");
        List<Map<String, Object>> e = closedEvents(allocationId);
        assertThat(e).hasSize(1);
        assertThat(((Number) e.get(0).get("line_count")).intValue()).isZero();
    }

    @Test
    @DisplayName("試練117 AC40 対象割当0件の締めは成功するが、締め期間行を作らない")
    void 試練117_対象0件で期間行を作らない() throws Exception {
        JsonNode res = data(close("2026-05").andExpect(status().isOk()));
        assertThat(res.path("closed_allocations").asInt()).isZero();
        assertThat(res.path("period_start").asText()).as("期間は応答には出す").isEqualTo("2026-05-01");
        assertThat(fixedPeriods(orgId)).isEmpty();
    }

    @Test
    @DisplayName("試練119 AC42 2件目の割当の Post が失敗しても1件目は確定し、再実行で2件目だけが締まる（重複0）")
    void 試練119_途中失敗と再実行() throws Exception {
        Long team2 = newTeamInOrg();
        Long worker2 = worker(team2, orgId);
        Long a = allocation(d("2026-04-01"), d("2026-04-30"));
        Long b = allocation(orgId, team2, fiscalYearId, categoryId, d("2026-04-01"), d("2026-04-30"));
        long[] sa = worked(d("2026-04-10"), workerId);
        long[] sb = worked(team2, d("2026-04-10"), worker2);
        doAnswer(inv -> {
            ShiftBudgetMonthlyPostCommand cmd = inv.getArgument(0);
            if (b.equals(cmd.allocationId())) {
                throw new IllegalStateException("2152α: 2件目の Post 失敗を模す");
            }
            return inv.callRealMethod();
        }).when(journal).post(any());

        close("2026-04");

        assertThat(liveStatus(sa[1], workerId)).isEqualTo("CONFIRMED");
        assertThat(journals(a)).hasSize(1);
        assertThat(liveStatus(sb[1], worker2)).isEqualTo("PLANNED");
        assertThat(journals(b)).isEmpty();

        org.mockito.Mockito.reset(journal);
        close("2026-04").andExpect(status().isOk());

        assertThat(liveStatus(sb[1], worker2)).isEqualTo("CONFIRMED");
        assertThat(journals(b)).hasSize(1);
        assertThat(journals(a)).as("1件目は再実行で重複しない").hasSize(1);
        assertThat(confirmedAmount(a)).isEqualByComparingTo("8000");
        assertThat(closedEvents(a)).hasSize(1);
        assertThat(closedEvents(b)).hasSize(1);
    }

    @Test
    @DisplayName("試練119 AC42 手動で 2026-06 を指定すると、未締めの 04・05 が古い順に先に締まる。境目をまたいだ再実行は未締めのみ")
    void 試練119_追いつき締め() throws Exception {
        Long allocationId = allocation(d("2026-04-01"), d("2026-06-30"));
        long[] s4 = worked(d("2026-04-10"), workerId);
        close("2026-04").andExpect(status().isOk());
        long[] s5 = worked(d("2026-05-10"), workerId);
        long[] s6 = worked(d("2026-06-10"), workerId);

        close("2026-06").andExpect(status().isOk());

        assertThat(liveStatus(s4[1], workerId)).isEqualTo("CONFIRMED");
        assertThat(liveStatus(s5[1], workerId)).isEqualTo("CONFIRMED");
        assertThat(liveStatus(s6[1], workerId)).isEqualTo("CONFIRMED");
        List<Map<String, Object>> e = closedEvents(allocationId);
        assertThat(e).extracting(m -> m.get("target_month")).containsExactly("2026-04", "2026-05", "2026-06");
        assertThat(journals(allocationId)).extracting(m -> m.get("transaction_date").toString())
                .containsExactly("2026-04-30", "2026-05-31", "2026-06-30");
        assertThat(confirmedAmount(allocationId)).isEqualByComparingTo("24000");

        close("2026-06").andExpect(status().isOk());
        assertThat(journals(allocationId)).as("再実行で重複0").hasSize(3);
        assertThat(confirmedAmount(allocationId)).isEqualByComparingTo("24000");
    }

    @Test
    @DisplayName("試練149 AC30α 未計上候補は期間と重なる勤務のみ（締め済み 2026-05 の 4/20 は候補、未締め 2026-06 の 5/20 は PLANNED）")
    void 試練149_候補は期間と重なる分のみ() throws Exception {
        fifteenthClosing();
        Long allocationId = allocation(d("2026-04-01"), d("2026-06-30"));
        Long worker2 = worker(teamId, orgId);
        long[] s420 = worked(d("2026-04-20"), workerId);
        long[] s520 = worked(d("2026-05-20"), workerId);
        close("2026-05").andExpect(status().isOk());

        assign(s420[1], workerId, worker2);
        publish(s420[0]);
        assign(s520[1], workerId, worker2);
        publish(s520[0]);

        assertThat(liveStatus(s520[1], worker2)).as("未締め期間の追加担当は通常記録").isEqualTo("PLANNED");
        JsonNode items = data(uncounted(adminId, allocationId, "2026-05").andExpect(status().isOk())).path("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).path("slot_id").asLong()).isEqualTo(s420[1]);
        assertThat(items.get(0).path("user_id").asLong()).isEqualTo(worker2);
        JsonNode april = data(uncounted(adminId, allocationId, "2026-04").andExpect(status().isOk())).path("items");
        assertThat(april).as("2026-04（3/16〜4/15）には勤務が無い").isEmpty();
    }
}
