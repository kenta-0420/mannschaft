package com.mannschaft.app.shiftbudget.closeperiod;

import com.mannschaft.app.budget.entity.BudgetConfigEntity;
import com.mannschaft.app.budget.repository.BudgetConfigRepository;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetClosePeriodResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260909-2152 PR-α 殿の決定（2026-11-18、PR #4615 試練報告への決定1〜8）で足した受け入れ条件の試練（実DB・実HTTP）。
 *
 * <p>出陣足軽が試練を先に追加して red を確認してから実装した（決定の末尾）。対応:
 * AC28e は {@code ClosePeriodBatchIT} 試練106e が正式な試練（JOURNAL_CONFLICT は自動再試行しない）、
 * AC49／51（決定8）は {@code ShiftBudgetClosePeriodStatsGuardArchTest}（Hibernate 統計の計測漏れが無いことの番人）。</p>
 *
 * <ul>
 *   <li>試練150 AC28f 機能フラグ OFF の組織は日次バッチの候補から外れる</li>
 *   <li>試練151 AC105b 同じ revision の締め日変更が同時に来たら一方だけ成功し他方は 409 SHIFT_BUDGET_038</li>
 *   <li>試練152 AC120b Prepare が割当を FOR UPDATE で取った時点で論理削除済みなら締めない。削除と Prepare の並行は削除側が 409</li>
 *   <li>試練153 AC137b 締め済み期間を割当範囲から外す更新は締め済みの割当削除と同じ 409（変更0）</li>
 *   <li>試練154 AC123b 期間一覧 API は from&gt;to・36か月超で 400</li>
 *   <li>試練155 AC139b 未締め（PREPARED を含む）の期間キーへの未計上候補 API は 409 BUDGET_024</li>
 * </ul>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260909-2152 α 殿の決定で足した受け入れ条件（実DB・実HTTP）")
class ClosePeriodDecisionAdditionsIT extends ClosePeriodTestBase {

    @Autowired private BudgetConfigRepository budgetConfigRepository;
    @Autowired private ShiftBudgetClosePeriodResolver periodResolver;

    // ───────────────────────── 試練150 AC28f ─────────────────────────

    @Test
    @DisplayName("試練150 AC28f 機能フラグ OFF の組織は日次バッチの候補から外れる（締め・イベント・失敗イベントとも0）")
    void 試練150_機能フラグOFFの組織は日次バッチ対象外() {
        Long allocationId = allocation(d("2026-04-01"), d("2026-04-30"));
        long[] s = worked(d("2026-04-10"), workerId);
        tx.executeWithoutResult(t -> budgetConfigRepository.save(BudgetConfigEntity.builder()
                .scopeType("ORGANIZATION").scopeId(orgId).shiftBudgetEnabled(false).build()));

        coordinator.closeAll(d("2026-05-01"));

        assertThat(closeEvents(allocationId)).isEmpty();
        assertThat(journals(allocationId)).isEmpty();
        assertThat(liveStatus(s[1], workerId)).isEqualTo("PLANNED");
        assertThat(failedEventsAny(orgId)).isZero();

        // 手動のバッチ入口（closeFromBatch）も機能フラグ OFF の組織は何もしない
        assertThat(coordinator.closeFromBatch(orgId, ym("2026-04")).closedAllocations()).isZero();
        assertThat(closeEvents(allocationId)).isEmpty();
    }

    // ───────────────────────── 試練151 AC105b ─────────────────────────

    @Test
    @DisplayName("試練151 AC105b 同じ revision の締め日変更が同時に2つ来たら、一方だけ成功し他方は 409 SHIFT_BUDGET_038。古い revision も 409")
    void 試練151_同じrevisionの同時変更は一方だけ成功() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<Integer> statuses = Collections.synchronizedList(new ArrayList<>());
        List<String> codes = Collections.synchronizedList(new ArrayList<>());
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int closingDay : new int[] {10, 20}) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    go.await(10, TimeUnit.SECONDS);
                    var result = changeClosingDay(adminId, orgId, 0, closingDay, "2026-11", "同時変更 " + closingDay)
                            .andReturn().getResponse();
                    statuses.add(result.getStatus());
                    codes.add(objectMapper.readTree(result.getContentAsString()).path("error").path("code").asText(""));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        go.countDown();
        for (CompletableFuture<Void> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }

        assertThat(statuses).as("一方だけ成功").containsExactlyInAnyOrder(200, 409);
        assertThat(codes).contains("SHIFT_BUDGET_038");
        assertThat(data(getSettings(adminId, orgId)).path("revision").asLong()).isEqualTo(1);
        Long rows = jdbc.queryForObject("SELECT COUNT(*) FROM shift_budget_closing_day_changes WHERE organization_id = ?",
                Long.class, orgId);
        assertThat(rows).isEqualTo(1);

        // 古い revision（0）はもう 409 SHIFT_BUDGET_038
        changeClosingDay(adminId, orgId, 0, 5, "2026-12", "古い revision").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SHIFT_BUDGET_038"));
    }

    // ───────────────────────── 試練152 AC120b ─────────────────────────

    @Test
    @DisplayName("試練152 AC120b Prepare が割当を FOR UPDATE で取った時点で論理削除済みなら、その割当は締めない（イベント・印0、失敗イベントなし）")
    void 試練152a_論理削除済みの割当はPrepareで締めない() {
        Long allocationId = allocation(d("2026-04-01"), d("2026-04-30"));
        long[] s = worked(d("2026-04-10"), workerId);
        periodResolver.ensureFixed(orgId, YearMonth.of(2026, 4), java.time.LocalDate.of(2099, 12, 31));
        jdbc.update("UPDATE shift_budget_allocations SET deleted_at = NOW() WHERE id = ?", allocationId);

        assertThat(stage.prepare(allocationId, ym("2026-04"))).isNull();

        assertThat(closeEvents(allocationId)).isEmpty();
        assertThat(markedCount(allocationId)).isZero();
        assertThat(liveStatus(s[1], workerId)).isEqualTo("PLANNED");
        assertThat(failedEventsAny(orgId)).isZero();
    }

    @Test
    @DisplayName("試練152 AC120b 削除と Prepare の並行: Prepare が割当ロックを握っている間の削除は待ち、Prepare 確定後は 409（PREPARED の割当が消えない）")
    void 試練152b_削除とPrepareの並行は削除側が409() throws Exception {
        Long allocationId = allocation(d("2026-04-01"), d("2026-04-30"));
        worked(d("2026-04-10"), workerId);
        CountDownLatch marked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            marked.countDown();
            release.await(30, TimeUnit.SECONDS);
            return null;
        }).when(hooks).afterPrepareMarked(anyLong(), any());

        CompletableFuture<Void> closing = CompletableFuture.runAsync(() -> {
            try {
                close("2026-04");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(marked.await(15, TimeUnit.SECONDS)).as("Prepare TX 内（afterPrepareMarked）に到達").isTrue();
        CompletableFuture<Integer> deleting = CompletableFuture.supplyAsync(() -> {
            try {
                return mvc.perform(as(delete("/api/v1/shift-budget/allocations/" + allocationId), adminId, orgId))
                        .andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(1000);
        assertThat(deleting.isDone()).as("削除は Prepare の割当ロックを待つ").isFalse();
        release.countDown();
        closing.get(60, TimeUnit.SECONDS);

        assertThat(deleting.get(60, TimeUnit.SECONDS)).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM shift_budget_allocations WHERE id = ?", Boolean.class,
                allocationId)).as("割当は消えない").isTrue();
        assertThat(closeEvents(allocationId)).extracting(e -> e.get("state")).containsExactly("CLOSED");
    }

    // ───────────────────────── 試練153 AC137b ─────────────────────────

    @Test
    @DisplayName("試練153 AC137b 締め済み期間を割当範囲から外す更新（PUT の period_start/period_end 変更）は、締め済みの割当削除と同じ 409 で拒否し変更0")
    void 試練153_締め済みの期間変更は削除と同じ409() throws Exception {
        Long allocationId = allocation(d("2026-04-01"), d("2026-05-31"));
        worked(d("2026-04-10"), workerId);
        close("2026-04").andExpect(status().isOk());
        Long version = jdbc.queryForObject("SELECT version FROM shift_budget_allocations WHERE id = ?", Long.class, allocationId);

        String deleteCode = objectMapper.readTree(mvc.perform(as(delete("/api/v1/shift-budget/allocations/" + allocationId),
                        adminId, orgId)).andExpect(status().isConflict()).andReturn().getResponse().getContentAsString())
                .path("error").path("code").asText();
        String body = objectMapper.writeValueAsString(Map.of("allocated_amount", 10000000, "version", version,
                "period_start", "2026-05-01", "period_end", "2026-05-31"));
        mvc.perform(as(put("/api/v1/shift-budget/allocations/" + allocationId), adminId, orgId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value(deleteCode));

        Map<String, Object> row = jdbc.queryForMap("SELECT period_start, period_end, version FROM shift_budget_allocations "
                + "WHERE id = ?", allocationId);
        assertThat(row.get("period_start").toString()).isEqualTo("2026-04-01");
        assertThat(row.get("period_end").toString()).isEqualTo("2026-05-31");
        assertThat(((Number) row.get("version")).longValue()).as("変更0").isEqualTo(version);
    }

    // ───────────────────────── 試練154 AC123b ─────────────────────────

    @Test
    @DisplayName("試練154 AC123b 期間一覧 API は from>to、または範囲が36か月を超えると 400。ちょうど36か月は 200")
    void 試練154_期間一覧の範囲検証() throws Exception {
        closePeriods(adminId, orgId, "2026-06", "2026-05").andExpect(status().isBadRequest());
        closePeriods(adminId, orgId, "2024-01", "2027-01").andExpect(status().isBadRequest());
        var ok = data(closePeriods(adminId, orgId, "2024-01", "2026-12").andExpect(status().isOk())).path("items");
        assertThat(ok).hasSize(36);
        closePeriods(adminId, orgId, "2026-05", "2026-05").andExpect(status().isOk());
    }

    // ───────────────────────── 試練155 AC139b ─────────────────────────

    @Test
    @DisplayName("試練155 AC139b 未締め（CLOSED イベントなし。PREPARED を含む）の期間キーへの未計上候補 API は 409 BUDGET_024")
    void 試練155_未締めの期間キーの候補は409() throws Exception {
        Long allocationId = allocation(d("2026-04-01"), d("2026-05-31"));
        worked(d("2026-04-10"), workerId);

        uncounted(adminId, allocationId, "2026-04").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BUDGET_024"));

        // PREPARED（Prepare コミット後・Post 前で停止）の期間キーも未締め扱い
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            reached.countDown();
            release.await(30, TimeUnit.SECONDS);
            return null;
        }).when(hooks).afterPrepared(anyLong(), any());
        CompletableFuture<Void> closing = CompletableFuture.runAsync(() -> {
            try {
                close("2026-04");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(reached.await(15, TimeUnit.SECONDS)).isTrue();
        uncounted(adminId, allocationId, "2026-04").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BUDGET_024"));
        release.countDown();
        closing.get(60, TimeUnit.SECONDS);

        uncounted(adminId, allocationId, "2026-04").andExpect(status().isOk());
    }
}
