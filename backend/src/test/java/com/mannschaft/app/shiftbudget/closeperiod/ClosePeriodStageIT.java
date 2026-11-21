package com.mannschaft.app.shiftbudget.closeperiod;

import com.mannschaft.app.shiftbudget.service.MonthlyShiftBudgetCloseCoordinator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260909-2152 PR-α 3段締め（Prepare／Post／Commit／Abort）の途中失敗・冪等・年度（実DB）。
 *
 * <p>試練120（AC43）・132（AC57）・134（AC59α 割当削除 409）・137（AC63）・138（AC64）・142a（AC69 IT）・143a（AC70 IT）。</p>
 *
 * <p>失敗注入は spy（{@code stage}・{@code journal}）と試験用フック {@code hooks} で行う。
 * 実装前の red 理由: 現行の締めは1 TX で段に分かれておらず、締めイベント・投稿判定・印の表が無い（SQL 文法エラー）、
 * フックが呼ばれない、段取り役の骨組みが UnsupportedOperationException を投げる。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260909-2152 α 3段締めの途中失敗・冪等・年度（実DB）")
class ClosePeriodStageIT extends ClosePeriodTestBase {

    private Long allocationId;
    private long[] s1;
    private long[] s2;

    private void seedApril() {
        allocationId = allocation(d("2026-04-01"), d("2026-04-30"));
        s1 = worked(d("2026-04-10"), workerId);
        s2 = worked(d("2026-04-20"), workerId);
    }

    private UUID eventId(Long allocation, String key) {
        byte[] raw = jdbc.queryForObject("SELECT id FROM shift_budget_monthly_close_events WHERE allocation_id = ? "
                + "AND target_month = ? AND state <> 'ABORTED'", byte[].class, allocation, key);
        ByteBuffer bb = ByteBuffer.wrap(raw);
        return new UUID(bb.getLong(), bb.getLong());
    }

    @Test
    @DisplayName("試練120a AC43 Prepare 失敗（印付け後・コミット前）→ イベント・印・確定・仕訳・判定行が何も残らない")
    void 試練120a_Prepare失敗() throws Exception {
        seedApril();
        doThrow(new IllegalStateException("2152α: Prepare 失敗を模す")).when(hooks).afterPrepareMarked(anyLong(), any());

        close("2026-04");

        Mockito.verify(hooks, Mockito.atLeastOnce()).afterPrepareMarked(anyLong(), any());
        assertThat(closeEvents(allocationId)).isEmpty();
        assertThat(markedCount(allocationId)).isZero();
        assertThat(liveStatus(s1[1], workerId)).isEqualTo("PLANNED");
        assertThat(journals(allocationId)).isEmpty();
        assertThat(postings(allocationId)).isEmpty();
        assertThat(confirmedAmount(allocationId)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("試練120b AC43 Post 失敗 → CONFIRMED・加算・仕訳・判定行は0、PREPARED と印は残り、再実行で1回だけ収束")
    void 試練120b_Post失敗() throws Exception {
        seedApril();
        doThrow(new IllegalStateException("2152α: Post 失敗を模す")).when(journal).post(any());

        close("2026-04");

        assertThat(closeEvents(allocationId)).extracting(e -> e.get("state")).containsExactly("PREPARED");
        assertThat(markedCount(allocationId)).isEqualTo(2);
        assertThat(liveStatus(s1[1], workerId)).isEqualTo("PLANNED");
        assertThat(confirmedAmount(allocationId)).isEqualByComparingTo("0");
        assertThat(journals(allocationId)).isEmpty();
        assertThat(postings(allocationId)).isEmpty();

        Mockito.reset(journal);
        close("2026-04").andExpect(status().isOk());

        assertThat(closeEvents(allocationId)).as("PREPARED を再開し新イベントを作らない")
                .extracting(e -> e.get("state")).containsExactly("CLOSED");
        assertThat(liveStatus(s1[1], workerId)).isEqualTo("CONFIRMED");
        assertThat(liveStatus(s2[1], workerId)).isEqualTo("CONFIRMED");
        assertThat(confirmedAmount(allocationId)).isEqualByComparingTo("16000");
        assertThat(journals(allocationId)).hasSize(1);
        assertThat(postings(allocationId)).extracting(p -> p.get("outcome")).containsExactly("POSTED");
    }

    @Test
    @DisplayName("試練120c AC43 Commit 失敗 → 判定 POSTED と仕訳1・PREPARED が残り、再実行で1回だけ確定（仕訳は増えない）")
    void 試練120c_Commit失敗() throws Exception {
        seedApril();
        doThrow(new IllegalStateException("2152α: Commit 失敗を模す")).when(stage).commit(any(), any());

        close("2026-04");

        assertThat(postings(allocationId)).extracting(p -> p.get("outcome")).containsExactly("POSTED");
        assertThat(journals(allocationId)).hasSize(1);
        assertThat(closeEvents(allocationId)).extracting(e -> e.get("state")).containsExactly("PREPARED");
        assertThat(liveStatus(s1[1], workerId)).isEqualTo("PLANNED");
        assertThat(confirmedAmount(allocationId)).isEqualByComparingTo("0");

        Mockito.reset(stage);
        close("2026-04").andExpect(status().isOk());

        assertThat(closeEvents(allocationId)).extracting(e -> e.get("state")).containsExactly("CLOSED");
        assertThat(journals(allocationId)).hasSize(1);
        assertThat(postings(allocationId)).hasSize(1);
        assertThat(confirmedAmount(allocationId)).isEqualByComparingTo("16000");
    }

    @Test
    @DisplayName("試練120d AC43 Commit の件数・合計不一致 → Commit ロールバック（部分変更0）・PREPARED/POSTED のまま・失敗イベント COMMIT_MISMATCH 1")
    void 試練120d_Commit不整合() throws Exception {
        seedApril();
        // Post 後・Commit 前に印付き PLANNED の金額をずらす（手作業の誤修正を模す）
        doAnswer(inv -> {
            jdbc.update("UPDATE shift_budget_consumptions SET amount = amount + 1 WHERE slot_id = ? AND user_id = ? "
                    + "AND status = 'PLANNED'", s1[1], workerId);
            return null;
        }).when(hooks).afterPosted(anyLong(), any(), any());

        close("2026-04");

        Mockito.verify(hooks).afterPosted(anyLong(), any(), any());
        assertThat(liveStatus(s1[1], workerId)).isEqualTo("PLANNED");
        assertThat(liveStatus(s2[1], workerId)).as("部分変更0").isEqualTo("PLANNED");
        assertThat(confirmedAmount(allocationId)).isEqualByComparingTo("0");
        assertThat(closeEvents(allocationId)).extracting(e -> e.get("state")).containsExactly("PREPARED");
        assertThat(postings(allocationId)).extracting(p -> p.get("outcome")).containsExactly("POSTED");
        assertThat(failedEvents(orgId, "COMMIT_MISMATCH")).isEqualTo(1);
    }

    @Test
    @DisplayName("試練132a AC57 Post 時に年度 CLOSED → 判定 ABORTED(FY_CLOSED)・イベント ABORTED 残存・印外れ・失敗イベント1、同組織の他割当は締まる")
    void 試練132a_年度CLOSEDでABORTED() throws Exception {
        Long closedFy = tx.execute(s -> insertFiscalYear(orgId, "2026-04-01", "2027-03-31", "OPEN"));
        Long closedCat = tx.execute(s -> insertCategory(closedFy));
        Long team2 = newTeamInOrg();
        Long worker2 = worker(team2, orgId);
        Long a = allocation(orgId, teamId, closedFy, closedCat, d("2026-04-01"), d("2026-04-30"));
        Long b = allocation(orgId, team2, fiscalYearId, categoryId, d("2026-04-01"), d("2026-04-30"));
        long[] sa = worked(d("2026-04-10"), workerId);
        long[] sb = worked(team2, d("2026-04-10"), worker2);
        setFiscalYearStatus(closedFy, "CLOSED");

        close("2026-04");

        assertThat(closeEvents(a)).extracting(e -> e.get("state")).containsExactly("ABORTED");
        assertThat(postings(a)).extracting(p -> p.get("outcome")).containsExactly("ABORTED");
        assertThat(postings(a).get(0).get("reason")).isEqualTo("FY_CLOSED");
        assertThat(markedCount(a)).as("印外れ").isZero();
        assertThat(liveStatus(sa[1], workerId)).isEqualTo("PLANNED");
        assertThat(journals(a)).isEmpty();
        assertThat(failedEvents(orgId, "POST_FY_CLOSED")).isEqualTo(1);
        assertThat(liveStatus(sb[1], worker2)).as("同組織の他割当は締まる").isEqualTo("CONFIRMED");
        assertThat(journals(b)).hasSize(1);

        // reopen 後の再締めは新しい試行が CLOSED に収束し、ABORTED は履歴に残る
        setFiscalYearStatus(closedFy, "OPEN");
        close("2026-04").andExpect(status().isOk());
        assertThat(closeEvents(a)).extracting(e -> e.get("state")).containsExactly("ABORTED", "CLOSED");
        assertThat(journals(a)).hasSize(1);
        assertThat(liveStatus(sa[1], workerId)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("試練132b AC57 年度クローズが年度行を握っている間の Post は待ち、クローズ確定後は ABORTED（CLOSED 年度に仕訳なし）")
    void 試練132b_年度クローズとPostの並行_クローズ先行() throws Exception {
        seedApril();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> fyClose = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            jdbc.queryForObject("SELECT id FROM budget_fiscal_years WHERE id = ? FOR UPDATE", Long.class, fiscalYearId);
            jdbc.update("UPDATE budget_fiscal_years SET status = 'CLOSED' WHERE id = ?", fiscalYearId);
            locked.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> closing = CompletableFuture.runAsync(() -> {
            try {
                close("2026-04");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(1500);
        release.countDown();
        fyClose.get(30, TimeUnit.SECONDS);
        closing.get(30, TimeUnit.SECONDS);

        assertThat(journals(allocationId)).as("CLOSED 年度に仕訳なし").isEmpty();
        assertThat(postings(allocationId)).extracting(p -> p.get("outcome")).containsExactly("ABORTED");
        assertThat(closeEvents(allocationId)).extracting(e -> e.get("state")).containsExactly("ABORTED");
    }

    @Test
    @DisplayName("試練132c AC57 Post が先に確定した後の年度クローズでは、仕訳は OPEN 年度で1件・イベント CLOSED")
    void 試練132c_年度クローズとPostの並行_Post先行() throws Exception {
        seedApril();
        close("2026-04").andExpect(status().isOk());
        setFiscalYearStatus(fiscalYearId, "CLOSED");
        assertThat(journals(allocationId)).hasSize(1);
        assertThat(closedEvents(allocationId)).hasSize(1);
        assertThat(postings(allocationId)).extracting(p -> p.get("outcome")).containsExactly("POSTED");
    }

    @Test
    @DisplayName("試練137 AC63 POSTED 後に年度 CLOSED → 再実行の Post は保存済み POSTED を受け取り、仕訳1・CLOSED")
    void 試練137_POSTED後の年度クローズ() throws Exception {
        seedApril();
        doThrow(new IllegalStateException("2152α: Commit 前に停止")).when(stage).commit(any(), any());
        close("2026-04");
        Mockito.reset(stage);
        setFiscalYearStatus(fiscalYearId, "CLOSED");

        close("2026-04");

        assertThat(closeEvents(allocationId)).extracting(e -> e.get("state")).containsExactly("CLOSED");
        assertThat(journals(allocationId)).hasSize(1);
        assertThat(postings(allocationId)).extracting(p -> p.get("outcome")).containsExactly("POSTED");
        assertThat(confirmedAmount(allocationId)).isEqualByComparingTo("16000");
    }

    @Test
    @DisplayName("試練138 AC64 Commit を2回呼んでもキャッシュ加算は1回分（2回目は CLOSED を見て何もしない）")
    void 試練138_Commitの冪等() throws Exception {
        seedApril();
        close("2026-04").andExpect(status().isOk());
        UUID event = eventId(allocationId, "2026-04");
        Long journalId = ((Number) journals(allocationId).get(0).get("id")).longValue();
        List<Map<String, Object>> before = consumptions(allocationId);

        stage.commit(event, journalId);

        assertThat(confirmedAmount(allocationId)).isEqualByComparingTo("16000");
        assertThat(consumptions(allocationId)).isEqualTo(before);
        assertThat(closedEvents(allocationId)).hasSize(1);
    }

    @Test
    @DisplayName("試練142a AC69 段取り役の公開入口を TX 内から呼ぶと IllegalStateException で拒否され、副作用0")
    void 試練142a_TX内からの呼出は拒否() throws Exception {
        seedApril();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> coordinator.close(orgId, ym("2026-04"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> coordinator.closeFromBatch(orgId, ym("2026-04"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> coordinator.closeAll(d("2026-05-01"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> coordinator.recoverDeferrals()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(closeEvents(allocationId)).isEmpty();
        assertThat(liveStatus(s1[1], workerId)).isEqualTo("PLANNED");
        assertThat(journals(allocationId)).isEmpty();
        assertThat(failedEventsAny(orgId)).as("失敗記録も残さない（Guard は最初の文）").isZero();
        // TX 外からは通る
        MonthlyShiftBudgetCloseCoordinator.CloseResult r = coordinator.closeFromBatch(orgId, ym("2026-04"));
        assertThat(r.closedAllocations()).isEqualTo(1);
    }

    @Test
    @DisplayName("試練143a AC70 判定行の無い PREPARED は回収・バッチ・再締めのどの経路でも ABORTED にならず、判定 POSTED で CLOSED へ進む")
    void 試練143a_判定なしPREPAREDのAbort経路なし() throws Exception {
        seedApril();
        doThrow(new IllegalStateException("2152α: Post 前に停止")).when(journal).post(any());
        close("2026-04");
        Mockito.reset(journal);
        assertThat(closeEvents(allocationId)).extracting(e -> e.get("state")).containsExactly("PREPARED");

        coordinator.recoverDeferrals();
        assertThat(closeEvents(allocationId)).extracting(e -> e.get("state")).containsExactly("PREPARED");
        assertThat(markedCount(allocationId)).isEqualTo(2);

        coordinator.closeAll(d("2026-05-01"));
        assertThat(closeEvents(allocationId)).extracting(e -> e.get("state")).containsExactly("CLOSED");
        assertThat(postings(allocationId)).extracting(p -> p.get("outcome")).containsExactly("POSTED");
    }

    @Test
    @DisplayName("試練134 AC59α 割当削除: CLOSED・PREPARED のイベントがあれば 409、ABORTED のみなら削除できる")
    void 試練134_割当削除409() throws Exception {
        // CLOSED
        seedApril();
        close("2026-04").andExpect(status().isOk());
        mvc.perform(as(delete("/api/v1/shift-budget/allocations/" + allocationId), adminId, orgId))
                .andExpect(status().isConflict());

        // PREPARED
        Long team2 = newTeamInOrg();
        Long worker2 = worker(team2, orgId);
        Long prepared = allocation(orgId, team2, fiscalYearId, categoryId, d("2026-05-01"), d("2026-05-31"));
        worked(team2, d("2026-05-10"), worker2);
        doThrow(new IllegalStateException("2152α: Post 前に停止")).when(journal).post(any());
        close("2026-05");
        Mockito.reset(journal);
        assertThat(closeEvents(prepared)).extracting(e -> e.get("state")).containsExactly("PREPARED");
        mvc.perform(as(delete("/api/v1/shift-budget/allocations/" + prepared), adminId, orgId))
                .andExpect(status().isConflict());

        // ABORTED のみ
        Long fy = tx.execute(s -> insertFiscalYear(orgId, "2026-04-01", "2027-03-31", "OPEN"));
        Long cat = tx.execute(s -> insertCategory(fy));
        Long team3 = newTeamInOrg();
        Long aborted = allocation(orgId, team3, fy, cat, d("2026-06-01"), d("2026-06-30"));
        setFiscalYearStatus(fy, "CLOSED");
        close("2026-06");
        assertThat(closeEvents(aborted)).extracting(e -> e.get("state")).containsExactly("ABORTED");
        mvc.perform(as(delete("/api/v1/shift-budget/allocations/" + aborted), adminId, orgId))
                .andExpect(status().is2xxSuccessful());
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM shift_budget_allocations WHERE id = ?",
                Boolean.class, aborted)).isTrue();
        assertThat(confirmedAmount(allocationId)).isEqualByComparingTo(new BigDecimal("16000"));
    }
}
