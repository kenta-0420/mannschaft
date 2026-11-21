package com.mannschaft.app.shiftbudget.closeperiod;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260909-2152 PR-α 日次締めバッチ・並行・性能（実DB）。
 *
 * <p>試練106（AC28）・122（AC45）・126（AC49）・128（AC51）。</p>
 *
 * <p>日次バッチ本体は {@code MonthlyShiftBudgetCloseCoordinator#closeAll(LocalDate)}（業務日を引数で受ける）。
 * 共有 DB の他試験の残骸も候補になりうるため、検証は自組織・自割当の行に限る。</p>
 *
 * <p>実装前の red 理由: 段取り役の骨組みが UnsupportedOperationException、締めイベント・期間表が無い、期間一覧 API が無い。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260909-2152 α 日次締めバッチ・並行・性能（実DB）")
class ClosePeriodBatchIT extends ClosePeriodTestBase {

    @Test
    @DisplayName("試練106a AC28 長期割当 4/1〜6/30 でも 5/1 の日次バッチで 2026-04 だけを締める。同日の再実行は冪等")
    void 試練106a_長期割当を月ごとに締める() {
        Long allocationId = allocation(d("2026-04-01"), d("2026-06-30"));
        long[] s4 = worked(d("2026-04-10"), workerId);
        long[] s5 = worked(d("2026-05-10"), workerId);

        coordinator.closeAll(d("2026-05-01"));

        assertThat(liveStatus(s4[1], workerId)).isEqualTo("CONFIRMED");
        assertThat(liveStatus(s5[1], workerId)).isEqualTo("PLANNED");
        assertThat(closedEvents(allocationId)).extracting(e -> e.get("target_month")).containsExactly("2026-04");
        assertThat(closedEvents(allocationId).get(0).get("actor_kind")).isEqualTo("BATCH");

        coordinator.closeAll(d("2026-05-01"));
        assertThat(journals(allocationId)).hasSize(1);
        assertThat(confirmedAmount(allocationId)).isEqualByComparingTo("8000");
    }

    @Test
    @DisplayName("試練106b AC28 3期間停止 → 再開日に古い順に3期間を締める")
    void 試練106b_停止後は古い順に追いつく() {
        Long allocationId = allocation(d("2026-04-01"), d("2026-06-30"));
        worked(d("2026-04-10"), workerId);
        worked(d("2026-05-10"), workerId);
        worked(d("2026-06-10"), workerId);

        coordinator.closeAll(d("2026-07-01"));

        List<Map<String, Object>> e = closedEvents(allocationId);
        assertThat(e).extracting(m -> m.get("target_month")).containsExactly("2026-04", "2026-05", "2026-06");
        assertThat(journals(allocationId)).extracting(m -> m.get("transaction_date").toString())
                .containsExactly("2026-04-30", "2026-05-31", "2026-06-30");
    }

    @Test
    @DisplayName("試練106c AC28 62日超の滞留（2026-01 の割当を 10/1 に）も回収する（時間窓なし）")
    void 試練106c_長期滞留も回収() {
        Long allocationId = allocation(d("2026-01-01"), d("2026-01-31"));
        long[] s = worked(d("2026-01-15"), workerId);

        coordinator.closeAll(d("2026-10-01"));

        assertThat(liveStatus(s[1], workerId)).isEqualTo("CONFIRMED");
        assertThat(closedEvents(allocationId)).hasSize(1);
    }

    @Test
    @DisplayName("試練106d AC28 上限（組織あたり36キー）超過は翌日継続（42期間 → 36＋6）")
    void 試練106d_36キー上限() {
        Long allocationId = allocation(d("2023-01-01"), d("2026-06-30"));

        coordinator.closeAll(d("2026-07-01"));
        assertThat(closedEvents(allocationId)).hasSize(36);
        assertThat(closedEvents(allocationId).get(35).get("target_month")).isEqualTo("2025-12");

        coordinator.closeAll(d("2026-07-02"));
        assertThat(closedEvents(allocationId)).hasSize(42);
        coordinator.closeAll(d("2026-07-03"));
        assertThat(closedEvents(allocationId)).hasSize(42);
    }

    @Test
    @DisplayName("試練106e AC28（決定4）同割当同仕訳日の生存仕訳がある → ABORTED(JOURNAL_CONFLICT)・失敗イベント1、日次バッチはその割当×キーを自動で締め直さない")
    void 試練106e_JOURNAL_CONFLICTは自動再試行しない() {
        Long allocationId = allocation(d("2026-04-01"), d("2026-04-30"));
        worked(d("2026-04-10"), workerId);
        jdbc.update("INSERT INTO budget_transactions (fiscal_year_id, category_id, scope_type, scope_id, transaction_type, "
                + "amount, transaction_date, title, is_auto_recorded, source_type, source_id, approval_status, recorded_by, "
                + "version, created_at, updated_at) VALUES (?, ?, 'TEAM', ?, 'EXPENSE', 1, '2026-04-30', '手作業の仕訳', 1, "
                + "'SHIFT_BUDGET_MONTHLY', ?, 'APPROVED', ?, 0, NOW(), NOW())",
                fiscalYearId, categoryId, teamId, allocationId, adminId);

        coordinator.closeAll(d("2026-05-01"));
        assertThat(postings(allocationId)).extracting(p -> p.get("reason")).containsExactly("JOURNAL_CONFLICT");
        assertThat(closeEvents(allocationId)).extracting(e -> e.get("state")).containsExactly("ABORTED");
        assertThat(failedEvents(orgId, "JOURNAL_CONFLICT")).isEqualTo(1);

        coordinator.closeAll(d("2026-05-02"));
        assertThat(closeEvents(allocationId)).as("バッチは自動で締め直さない").hasSize(1);
        assertThat(failedEvents(orgId, "JOURNAL_CONFLICT")).isEqualTo(1);
    }

    @Test
    @DisplayName("CMP-260909-2152 Codex 再検分 P2-2 途中の期間の Prepare が失敗したら、その割当は後続の期間へ進まない（締めは先頭から途切れない）。翌日の日次バッチで失敗した期間から古い順に回収する")
    void 再検分P2_2_途中失敗で後続期間へ進まず翌日に回収する() {
        Long allocationId = allocation(d("2026-04-01"), d("2026-06-30"));
        long[] s4 = worked(d("2026-04-10"), workerId);
        worked(d("2026-05-10"), workerId);
        worked(d("2026-06-10"), workerId);
        // 2026-04 の Prepare だけ1回失敗させる（締めイベントは残らない）
        doThrow(new IllegalStateException("prepare failed (test)")).doCallRealMethod()
                .when(stage).prepare(eq(allocationId), eq(java.time.YearMonth.of(2026, 4)));

        coordinator.closeAll(d("2026-07-01"));
        assertThat(closedEvents(allocationId)).as("先頭が締まらないまま後続の期間を締めない").isEmpty();

        coordinator.closeAll(d("2026-07-02"));
        assertThat(closedEvents(allocationId)).extracting(e -> e.get("target_month"))
                .containsExactly("2026-04", "2026-05", "2026-06");
        assertThat(liveStatus(s4[1], workerId)).as("過去の PLANNED を自動回収する").isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("試練122 AC45 手動締めとバッチの同時実行で、期間行1・締め1・仕訳1・確定額1回分に収束する（犠牲 TX は0件または1回の再実行で一致）")
    void 試練122_手動とバッチの同時実行() throws Exception {
        Long allocationId = allocation(d("2026-04-01"), d("2026-04-30"));
        worked(d("2026-04-10"), workerId);
        CountDownLatch bothStarted = new CountDownLatch(2);
        doAnswer(inv -> {
            bothStarted.countDown();
            bothStarted.await(3, TimeUnit.SECONDS);
            return inv.callRealMethod();
        }).when(hooks).afterPrepared(anyLong(), any());

        CompletableFuture<Integer> manual = CompletableFuture.supplyAsync(() -> {
            try {
                return close("2026-04").andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        CompletableFuture<Void> batch = CompletableFuture.runAsync(
                () -> coordinator.closeFromBatch(orgId, ym("2026-04")));
        int manualStatus = manual.get(60, TimeUnit.SECONDS);
        batch.handle((r, e) -> null).get(60, TimeUnit.SECONDS);
        if (closedEvents(allocationId).isEmpty()) {
            // 犠牲 TX があった場合は1回の再実行で一致すること
            coordinator.closeFromBatch(orgId, ym("2026-04"));
        }

        assertThat(manualStatus).isIn(200, 409);
        assertThat(fixedPeriods(orgId)).hasSize(1);
        assertThat(closedEvents(allocationId)).hasSize(1);
        assertThat(journals(allocationId)).hasSize(1);
        assertThat(confirmedAmount(allocationId)).isEqualByComparingTo("8000");
    }

    private Statistics stats() {
        Statistics s = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        s.setStatisticsEnabled(true);
        return s;
    }

    @Test
    @DisplayName("試練126 AC49 期間一覧の解決で N+1 なし（2か月と24か月で発行文数が同じ）")
    void 試練126_期間解決のN1なし() throws Exception {
        today(d("2025-01-01"));
        setClosingDay(15, "2025-03");
        setClosingDay(10, "2025-09");
        setClosingDay(0, "2026-03");
        today(DEFAULT_TODAY);
        closePeriods(adminId, orgId, "2025-01", "2025-02").andExpect(status().isOk());

        Statistics s = stats();
        s.clear();
        closePeriods(adminId, orgId, "2025-01", "2025-02").andExpect(status().isOk());
        long small = s.getPrepareStatementCount();
        s.clear();
        var items = data(closePeriods(adminId, orgId, "2025-01", "2026-12").andExpect(status().isOk())).path("items");
        long large = s.getPrepareStatementCount();

        assertThat(items).hasSize(24);
        assertThat(large).as("期間数に比例して文が増えない（small=%d）", small).isLessThanOrEqualTo(small);
    }

    @Test
    @DisplayName("試練128 AC51 日次バッチの候補抽出は対象外組織に文を発行しない（対象外組織を5つ足しても発行文数が増えない）")
    void 試練128_対象外組織にクエリなし() {
        coordinator.closeAll(d("2026-05-01")); // 共有 DB の残骸を先に片付ける（以降の差分は自組織だけ）
        allocation(d("2026-04-01"), d("2026-04-30"));
        worked(d("2026-04-10"), workerId);
        Statistics s = stats();
        s.clear();
        coordinator.closeAll(d("2026-05-01"));
        long base = s.getPrepareStatementCount();

        long[] x = otherOrganization();
        allocation(x[0], x[1], x[3], x[4], d("2026-04-01"), d("2026-04-30"));
        for (int i = 0; i < 5; i++) {
            long[] o = otherOrganization();
            // 開始が昨日より後の割当だけを持つ組織＝候補外
            allocation(o[0], o[1], o[3], o[4], d("2026-06-01"), d("2026-06-30"));
        }
        s.clear();
        coordinator.closeAll(d("2026-05-01"));
        long withNonTargets = s.getPrepareStatementCount();

        assertThat(withNonTargets).as("対象外組織5つで文が増えない（base=%d）", base).isLessThanOrEqualTo(base);
    }
}
