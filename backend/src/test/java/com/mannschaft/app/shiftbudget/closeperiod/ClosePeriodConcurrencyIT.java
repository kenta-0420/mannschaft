package com.mannschaft.app.shiftbudget.closeperiod;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.shift.event.ShiftPublishedEvent;
import com.mannschaft.app.shift.event.ShiftScheduleCloseReason;
import com.mannschaft.app.shift.event.ShiftScheduleClosedEvent;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetRecordDeferralService.ReplayOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260909-2152 PR-α 締めと記録・取消・再公開の並行、遅延記録（実DB）。
 *
 * <p>試練121（AC44）・130（AC54）・131（AC56）・136（AC61）・139（AC65）・140（AC66）・141（AC67）・144（AC71）・
 * 145（AC72）・148（AC78）。順序は試験用フック {@code ShiftBudgetLockStepHooks}（spy）で固定する。
 * 並行系は「犠牲 TX 0件、または1回の再実行で一致」・デッドロックなし（全スレッドが時間内に終わる）を確認する。</p>
 *
 * <p>実装前の red 理由: フックが呼ばれず待ち合わせ点に到達しない（「フックに到達」の検査で失敗）、
 * 遅延記録・締めイベント表が無い、段取り役の骨組みが UnsupportedOperationException。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260909-2152 α 締めと記録・取消の並行、遅延記録（実DB）")
class ClosePeriodConcurrencyIT extends ClosePeriodTestBase {

    @Autowired private ApplicationEventPublisher events;

    private static final int WAIT_SEC = 15;

    // ───────────────────────── 待ち合わせ ─────────────────────────

    @FunctionalInterface
    interface Step {
        void run() throws Exception;
    }

    private static CompletableFuture<Void> async(Step step) {
        return CompletableFuture.runAsync(() -> {
            try {
                step.run();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** latch に到達するまで待つ。対象スレッドが先に終わった（＝フックが呼ばれなかった）なら即失敗。 */
    private static void awaitReached(CountDownLatch reached, CompletableFuture<?> worker, String what) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SEC);
        while (System.nanoTime() < deadline) {
            if (reached.await(100, TimeUnit.MILLISECONDS)) {
                return;
            }
            if (worker != null && worker.isDone()) {
                break;
            }
        }
        throw new AssertionError("待ち合わせ点に到達しない: " + what
                + (worker != null && worker.isDone() ? "（処理はフックを通らずに終わった）" : ""));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 締め（手動・期間キー）を別スレッドで走らせ、Prepare コミット後（Post 前）で止める。 */
    private final class PausedClose {
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> worker;

        PausedClose start(String key) throws Exception {
            AtomicInteger calls = new AtomicInteger();
            doAnswer(inv -> {
                if (calls.getAndIncrement() == 0) {
                    reached.countDown();
                    ClosePeriodConcurrencyIT.await(release);
                }
                return null;
            }).when(hooks).afterPrepared(anyLong(), any());
            worker = async(() -> close(key));
            awaitReached(reached, worker, "Prepare コミット後（afterPrepared）");
            return this;
        }

        void finish() throws Exception {
            release.countDown();
            worker.get(60, TimeUnit.SECONDS);
        }
    }

    private Long allocationId;

    private void aprilMay() {
        allocationId = allocation(d("2026-04-01"), d("2026-05-31"));
    }

    private long liveRows(long slotId, Long userId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM shift_budget_consumptions WHERE slot_id = ? AND user_id = ? "
                + "AND deleted_at IS NULL AND status IN ('PLANNED','CONFIRMED')", Long.class, slotId, userId);
        return n == null ? 0 : n;
    }

    /** キャッシュ consumed_amount が生存 PLANNED＋CONFIRMED の合計と一致すること（減算・加算の漏れ・二重がない）。 */
    private void assertCacheConsistent(Long allocation) {
        BigDecimal live = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM shift_budget_consumptions "
                + "WHERE allocation_id = ? AND deleted_at IS NULL AND status IN ('PLANNED','CONFIRMED')",
                BigDecimal.class, allocation);
        assertThat(consumedAmount(allocation)).as("consumed_amount キャッシュ＝生存行の合計").isEqualByComparingTo(live);
    }

    private String cancelReason(long slotId, Long userId) {
        List<String> r = jdbc.queryForList("SELECT cancel_reason FROM shift_budget_consumptions WHERE slot_id = ? "
                + "AND user_id = ? AND status = 'CANCELLED' ORDER BY id DESC", String.class, slotId, userId);
        return r.isEmpty() ? null : r.get(0);
    }

    /** 既存シフトに枠を足す。 */
    private long addSlot(long scheduleId, LocalDate date, Long... users) {
        return tx.execute(s -> {
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < users.length; i++) {
                b.append(i == 0 ? "" : ",").append(users[i]);
            }
            ShiftSlotEntity slot = ShiftSlotEntity.builder().scheduleId(scheduleId).slotDate(date)
                    .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(17, 0)).requiredCount(users.length)
                    .assignedUserIds(b.append(']').toString()).build();
            em.persist(slot);
            em.flush();
            return slot.getId();
        });
    }

    // ───────────────────────── 試練121 AC44 ─────────────────────────

    @Test
    @DisplayName("試練121 AC44 締め（Prepare 後）と同じ期間キーの締め日変更が競合 → 変更は 409 038、締め期間の境界は不変（犠牲 TX 0件）")
    void 試練121_締めと締め日変更の競合() throws Exception {
        today(d("2026-02-01"));
        setClosingDay(15, "2026-03");
        today(d("2026-05-20"));
        Long a = allocation(d("2026-04-16"), d("2026-05-31"));
        worked(d("2026-04-20"), workerId);

        PausedClose p = new PausedClose().start("2026-05");
        changeClosingDay(adminId, orgId, 1, 20, "2026-05", "締めと競合").andExpect(status().isConflict());
        // 未確定の後続期間への変更は受理されるが、確定済み期間の境界は動かない
        changeClosingDay(adminId, orgId, 1, 20, "2026-06", "後続期間").andExpect(status().is2xxSuccessful());
        p.finish();

        List<Map<String, Object>> fixed = fixedPeriods(orgId);
        assertThat(fixed).hasSize(1);
        assertThat(fixed.get(0).get("period_start").toString()).isEqualTo("2026-04-16");
        assertThat(fixed.get(0).get("period_end").toString()).isEqualTo("2026-05-15");
        assertThat(journals(a).get(0).get("transaction_date").toString()).isEqualTo("2026-05-15");
        assertThat(closedEvents(a)).hasSize(1);
    }

    // ───────────────────────── 試練130 AC54 ─────────────────────────

    @Test
    @DisplayName("試練130a AC54 勤務枠を未締め期間へ移動 → 移動先の期間で締まる（移動元の締めでは確定しない）")
    void 試練130a_未締め期間へ移動() throws Exception {
        aprilMay();
        long[] s = worked(d("2026-04-10"), workerId);
        moveSlot(s[1], d("2026-05-10"));

        close("2026-04").andExpect(status().isOk());
        assertThat(liveStatus(s[1], workerId)).isEqualTo("PLANNED");
        close("2026-05").andExpect(status().isOk());
        assertThat(liveStatus(s[1], workerId)).isEqualTo("CONFIRMED");
        assertThat(closedEvents(allocationId)).extracting(e -> ((Number) e.get("line_count")).intValue())
                .containsExactly(0, 1);
    }

    @Test
    @DisplayName("試練130b AC54 勤務枠を締め済み期間へ移動 → SLOT_MOVED_TO_CLOSED_PERIOD で取消・未計上候補に出る")
    void 試練130b_締め済み期間へ移動() throws Exception {
        aprilMay();
        worked(d("2026-04-12"), workerId);
        close("2026-04").andExpect(status().isOk());
        long[] y = worked(d("2026-05-12"), workerId);
        moveSlot(y[1], d("2026-04-20"));

        close("2026-05").andExpect(status().isOk());

        assertThat(liveStatus(y[1], workerId)).isNull();
        assertThat(cancelReason(y[1], workerId)).isEqualTo("SLOT_MOVED_TO_CLOSED_PERIOD");
        assertCacheConsistent(allocationId);
        JsonNode items = data(uncounted(adminId, allocationId, "2026-04").andExpect(status().isOk())).path("items");
        assertThat(items).extracting(i -> i.path("slot_id").asLong()).contains(y[1]);
    }

    @Test
    @DisplayName("試練130c AC54 勤務枠を割当範囲外へ移動 → SLOT_MOVED_OUT_OF_ALLOCATION で取消・失敗イベント1。CONFIRMED の枠移動は計上先を動かさない")
    void 試練130c_割当範囲外へ移動とCONFIRMED不変() throws Exception {
        aprilMay();
        long[] x = worked(d("2026-04-12"), workerId);
        close("2026-04").andExpect(status().isOk());
        long[] z = worked(d("2026-05-14"), workerId);
        moveSlot(z[1], d("2026-06-14"));
        moveSlot(x[1], d("2026-05-01")); // 確定後の枠日付変更
        long failedBefore = failedEventsAny(orgId);

        close("2026-05").andExpect(status().isOk());

        assertThat(liveStatus(z[1], workerId)).isNull();
        assertThat(cancelReason(z[1], workerId)).isEqualTo("SLOT_MOVED_OUT_OF_ALLOCATION");
        assertThat(failedEventsAny(orgId) - failedBefore).isEqualTo(1);
        assertThat(liveStatus(x[1], workerId)).as("CONFIRMED は不変").isEqualTo("CONFIRMED");
        assertThat(journals(allocationId)).extracting(j -> ((BigDecimal) j.get("amount")).intValue())
                .as("2026-05 の仕訳に移動した確定済み勤務を含めない").containsExactly(8000, 0);
        assertCacheConsistent(allocationId);
    }

    // ───────────────────────── 試練131 AC56 ─────────────────────────

    @Test
    @DisplayName("試練131a AC56 印より前の取消は締めの合計外（取消済み行は確定せず、仕訳は残りの分のみ）")
    void 試練131a_印前の取消() throws Exception {
        aprilMay();
        long[] s1 = worked(d("2026-04-10"), workerId);
        worked(d("2026-04-11"), workerId);
        archive(s1[0]);

        close("2026-04").andExpect(status().isOk());

        assertThat((BigDecimal) journals(allocationId).get(0).get("amount")).isEqualByComparingTo("8000");
        assertThat(((Number) closedEvents(allocationId).get(0).get("line_count")).intValue()).isEqualTo(1);
        assertCacheConsistent(allocationId);
    }

    @Test
    @DisplayName("試練131b AC56 印後（PREPARED 中）の取消は遅延 → Commit 後は CONFIRMED 不変・CANCEL_AFTER_CLOSE 1・遅延 REPLAYED。デッドロックなし")
    void 試練131b_印後の取消はCommit後CANCEL_AFTER_CLOSE() throws Exception {
        aprilMay();
        long[] s1 = worked(d("2026-04-10"), workerId);

        PausedClose p = new PausedClose().start("2026-04");
        archive(s1[0]);
        assertThat(liveStatus(s1[1], workerId)).as("印付き PLANNED は取消さない").isEqualTo("PLANNED");
        assertThat(deferrals(s1[0])).extracting(r -> r.get("state")).containsExactly("PENDING");
        p.finish();

        assertThat(liveStatus(s1[1], workerId)).isEqualTo("CONFIRMED");
        assertThat(failedEvents(orgId, "CANCEL_AFTER_CLOSE")).isEqualTo(1);
        assertThat(deferrals(s1[0])).extracting(r -> r.get("state")).containsExactly("REPLAYED");
        assertCacheConsistent(allocationId);
    }

    @Test
    @DisplayName("試練131c AC56 印後の取消 → Abort（年度 CLOSED）後は取消が適用され減算される")
    void 試練131c_印後の取消はAbort後に適用() throws Exception {
        aprilMay();
        long[] s1 = worked(d("2026-04-10"), workerId);

        PausedClose p = new PausedClose().start("2026-04");
        archive(s1[0]);
        setFiscalYearStatus(fiscalYearId, "CLOSED");
        p.finish();

        assertThat(closeEvents(allocationId)).extracting(e -> e.get("state")).containsExactly("ABORTED");
        assertThat(liveStatus(s1[1], workerId)).isNull();
        assertThat(consumedAmount(allocationId)).isEqualByComparingTo("0");
        assertThat(deferrals(s1[0])).extracting(r -> r.get("state")).containsExactly("REPLAYED");
    }

    @Test
    @DisplayName("試練131d AC56 Commit 後の取消は CONFIRMED を取消さず CANCEL_AFTER_CLOSE 1")
    void 試練131d_Commit後の取消() throws Exception {
        aprilMay();
        long[] s1 = worked(d("2026-04-10"), workerId);
        close("2026-04").andExpect(status().isOk());

        archive(s1[0]);

        assertThat(liveStatus(s1[1], workerId)).isEqualTo("CONFIRMED");
        assertThat(failedEvents(orgId, "CANCEL_AFTER_CLOSE")).isEqualTo(1);
    }

    // ───────────────────────── 試練136 AC61 ─────────────────────────

    @Test
    @DisplayName("試練136a AC61 Prepare のロック待ち中の再公開は、Prepare コミット後に current read で PREPARED／CLOSED を検出し新 PLANNED 0（候補へ）")
    void 試練136a_Prepareロック待ちの再公開() throws Exception {
        aprilMay();
        Long worker2 = worker(teamId, orgId);
        long[] s1 = worked(d("2026-04-10"), workerId);
        CountDownLatch marked = new CountDownLatch(1);
        CountDownLatch releaseMarked = new CountDownLatch(1);
        doAnswer(inv -> {
            marked.countDown();
            await(releaseMarked);
            return null;
        }).when(hooks).afterPrepareMarked(anyLong(), any());

        CompletableFuture<Void> closing = async(() -> close("2026-04"));
        awaitReached(marked, closing, "Prepare TX 内（afterPrepareMarked）");
        assign(s1[1], workerId, worker2);
        CompletableFuture<Void> republish = async(() -> publish(s1[0]));
        Thread.sleep(1000);
        assertThat(republish.isDone()).as("再公開の記録は割当ロックを待つ").isFalse();
        releaseMarked.countDown();
        closing.get(60, TimeUnit.SECONDS);
        republish.get(60, TimeUnit.SECONDS);
        drainEventPool();

        assertThat(liveStatus(s1[1], workerId)).isEqualTo("CONFIRMED");
        assertThat(liveRows(s1[1], worker2)).as("締め済み期間に新 PLANNED 0").isZero();
        assertThat(data(uncounted(adminId, allocationId, "2026-04")).path("items"))
                .extracting(i -> i.path("user_id").asLong()).contains(worker2);
        assertCacheConsistent(allocationId);
    }

    @Test
    @DisplayName("試練136b AC61 締めと日付移動再公開の並行 →「M で CONFIRMED・再計上なし」か「旧行取消・N で PLANNED」のどちらか一方（生存行1）")
    void 試練136b_締めと日付移動再公開() throws Exception {
        aprilMay();
        long[] s1 = worked(d("2026-04-10"), workerId);
        CountDownLatch marked = new CountDownLatch(1);
        CountDownLatch releaseMarked = new CountDownLatch(1);
        doAnswer(inv -> {
            marked.countDown();
            await(releaseMarked);
            return null;
        }).when(hooks).afterPrepareMarked(anyLong(), any());

        CompletableFuture<Void> closing = async(() -> close("2026-04"));
        awaitReached(marked, closing, "Prepare TX 内（afterPrepareMarked）");
        moveSlot(s1[1], d("2026-05-10"));
        CompletableFuture<Void> republish = async(() -> publish(s1[0]));
        Thread.sleep(500);
        releaseMarked.countDown();
        closing.get(60, TimeUnit.SECONDS);
        republish.get(60, TimeUnit.SECONDS);
        drainEventPool();
        coordinator.recoverDeferrals();

        assertThat(liveRows(s1[1], workerId)).as("生存行はちょうど1").isEqualTo(1);
        String st = liveStatus(s1[1], workerId);
        assertThat(st).isIn("CONFIRMED", "PLANNED");
        if ("CONFIRMED".equals(st)) {
            assertThat(confirmedAmount(allocationId)).isEqualByComparingTo("8000");
        } else {
            assertThat(confirmedAmount(allocationId)).isEqualByComparingTo("0");
        }
        assertCacheConsistent(allocationId);
    }

    // ───────────────────────── 試練139 AC65 ─────────────────────────

    @Test
    @DisplayName("試練139a AC65 PREPARED 中の日付移動再公開: 印付き行は取消されず遅延行はシフトにつき1行、Commit 後は M で CONFIRMED・再計上0")
    void 試練139a_PREPARED中の日付移動_Commit() throws Exception {
        aprilMay();
        long[] s1 = worked(d("2026-04-10"), workerId);

        PausedClose p = new PausedClose().start("2026-04");
        moveSlot(s1[1], d("2026-05-10"));
        publish(s1[0]);
        publish(s1[0]);
        assertThat(liveStatus(s1[1], workerId)).isEqualTo("PLANNED");
        assertThat(deferrals(s1[0])).as("PENDING はシフトにつき1行").hasSize(1);
        p.finish();

        assertThat(liveStatus(s1[1], workerId)).isEqualTo("CONFIRMED");
        assertThat(liveRows(s1[1], workerId)).isEqualTo(1);
        assertThat(confirmedAmount(allocationId)).isEqualByComparingTo("8000");
        assertCacheConsistent(allocationId);
    }

    @Test
    @DisplayName("試練139b AC65 PREPARED 中の日付移動再公開 → Abort 後は旧行取消・N（5/10）で PLANNED")
    void 試練139b_PREPARED中の日付移動_Abort() throws Exception {
        aprilMay();
        long[] s1 = worked(d("2026-04-10"), workerId);

        PausedClose p = new PausedClose().start("2026-04");
        moveSlot(s1[1], d("2026-05-10"));
        publish(s1[0]);
        setFiscalYearStatus(fiscalYearId, "CLOSED");
        p.finish();

        assertThat(liveStatus(s1[1], workerId)).isEqualTo("PLANNED");
        assertThat(liveRows(s1[1], workerId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shift_budget_consumptions WHERE slot_id = ? "
                + "AND status = 'CANCELLED'", Long.class, s1[1])).as("旧行は取消").isEqualTo(1L);
        assertThat(markedCount(allocationId)).isZero();
        assertCacheConsistent(allocationId);
    }

    // ───────────────────────── 試練140 AC66 ─────────────────────────

    @Test
    @DisplayName("試練140a AC66 PREPARED 中の担当者追加 → 遅延。Abort 後は同期再実行で recorded=1・REPLAYED・PLANNED1・加算")
    void 試練140a_担当者追加_Abort() throws Exception {
        aprilMay();
        Long worker2 = worker(teamId, orgId);
        long[] s1 = worked(d("2026-04-10"), workerId);

        PausedClose p = new PausedClose().start("2026-04");
        assign(s1[1], workerId, worker2);
        publish(s1[0]);
        assertThat(liveRows(s1[1], worker2)).isZero();
        assertThat(deferrals(s1[0])).extracting(r -> r.get("state")).containsExactly("PENDING");
        setFiscalYearStatus(fiscalYearId, "CLOSED");
        p.finish();

        assertThat(liveStatus(s1[1], worker2)).isEqualTo("PLANNED");
        assertThat(deferrals(s1[0])).extracting(r -> r.get("state")).containsExactly("REPLAYED");
        assertThat(consumedAmount(allocationId)).isEqualByComparingTo("16000");
        assertCacheConsistent(allocationId);
    }

    @Test
    @DisplayName("試練140b AC66 PREPARED 中の担当者追加 → Commit 後は SKIPPED_CLOSED で REPLAYED・候補1")
    void 試練140b_担当者追加_Commit() throws Exception {
        aprilMay();
        Long worker2 = worker(teamId, orgId);
        long[] s1 = worked(d("2026-04-10"), workerId);

        PausedClose p = new PausedClose().start("2026-04");
        assign(s1[1], workerId, worker2);
        publish(s1[0]);
        p.finish();

        assertThat(liveRows(s1[1], worker2)).isZero();
        assertThat(deferrals(s1[0])).extracting(r -> r.get("state")).containsExactly("REPLAYED");
        JsonNode items = data(uncounted(adminId, allocationId, "2026-04")).path("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).path("user_id").asLong()).isEqualTo(worker2);
    }

    @Test
    @DisplayName("試練140c/144a AC66・AC71 再実行前・再実行中の障害は PENDING（attempt+1・last_error・所有解除）で残り、回収バッチが REPLAYED にする")
    void 試練140c_再実行障害はバッチが回収() throws Exception {
        aprilMay();
        Long worker2 = worker(teamId, orgId);
        long[] s1 = worked(d("2026-04-10"), workerId);
        doAnswer(inv -> {
            throw new IllegalStateException("2152α: 再実行中の障害を模す");
        }).when(hooks).afterDeferralClaimed(any(), anyString());

        PausedClose p = new PausedClose().start("2026-04");
        assign(s1[1], workerId, worker2);
        publish(s1[0]);
        setFiscalYearStatus(fiscalYearId, "CLOSED");
        p.finish();

        List<Map<String, Object>> d1 = deferrals(s1[0]);
        assertThat(d1).hasSize(1);
        assertThat(d1.get(0).get("state")).isEqualTo("PENDING");
        assertThat(((Number) d1.get(0).get("attempt_count")).intValue()).isEqualTo(1);
        assertThat((String) d1.get(0).get("last_error")).contains("再実行中の障害");
        assertThat(d1.get(0).get("claimed_by")).as("所有解除").isNull();

        org.mockito.Mockito.reset(hooks);
        coordinator.recoverDeferrals();

        assertThat(deferrals(s1[0])).extracting(r -> r.get("state")).containsExactly("REPLAYED");
        assertThat(liveStatus(s1[1], worker2)).isEqualTo("PLANNED");
    }

    // ───────────────────────── 試練141 AC67 ─────────────────────────

    @Test
    @DisplayName("試練141a AC67 発見後に別の再公開が Y へ移動 → ロック集合不一致でロールバックし {T, Y} で再試行、生存行1、非ロック割当は不変")
    void 試練141a_ロック集合不一致は再試行() throws Exception {
        Long a = allocation(d("2026-04-01"), d("2026-04-30"));
        Long b = allocation(d("2026-05-01"), d("2026-05-31"));
        long[] s1 = worked(d("2026-04-10"), workerId);
        Long team2 = newTeamInOrg();
        Long c = allocation(orgId, team2, fiscalYearId, categoryId, d("2026-04-01"), d("2026-05-31"));
        BigDecimal cBefore = consumedAmount(c);

        CountDownLatch discovered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(inv -> {
            if (calls.getAndIncrement() == 0) {
                discovered.countDown();
                await(release);
            }
            return null;
        }).when(hooks).afterRecordDiscovered(anyLong(), anyLong(), anyLong());

        CompletableFuture<Void> r1 = async(() -> publish(s1[0]));
        awaitReached(discovered, r1, "記録の発見後（afterRecordDiscovered）");
        moveSlot(s1[1], d("2026-05-10"));
        CompletableFuture<Void> r2 = async(() -> publish(s1[0]));
        Thread.sleep(1000);
        release.countDown();
        r1.get(60, TimeUnit.SECONDS);
        r2.get(60, TimeUnit.SECONDS);
        drainEventPool();

        assertThat(liveRows(s1[1], workerId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT allocation_id FROM shift_budget_consumptions WHERE slot_id = ? "
                + "AND status = 'PLANNED' AND deleted_at IS NULL", Long.class, s1[1])).isEqualTo(b);
        assertThat(consumedAmount(a)).isEqualByComparingTo("0");
        assertThat(consumedAmount(b)).isEqualByComparingTo("8000");
        assertThat(consumedAmount(c)).as("非ロック割当は変更しない").isEqualByComparingTo(cBefore);
        assertThat(calls.get()).as("不一致の後に計上先解決からやり直す").isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("試練141c AC67 勤務日の再解決（ロック集合不一致の再試行）で、時給も再解決後の勤務日で取り直す（時給の切替日 5/1 をまたいで 4/10 → 5/10 へ移動 → 1,200 円で記録）")
    void 試練141c_再解決後の勤務日で時給を取り直す() throws Exception {
        allocation(d("2026-04-01"), d("2026-04-30"));
        Long b = allocation(d("2026-05-01"), d("2026-05-31"));
        long[] s1 = worked(d("2026-04-10"), workerId);
        Long team2 = newTeamInOrg();
        Long c = allocation(orgId, team2, fiscalYearId, categoryId, d("2026-04-01"), d("2026-05-31"));
        tx.executeWithoutResult(t -> em.persist(com.mannschaft.app.shift.entity.ShiftHourlyRateEntity.builder()
                .userId(workerId).teamId(teamId).hourlyRate(new BigDecimal("1200"))
                .effectiveFrom(d("2026-05-01")).build()));

        AtomicInteger calls = new AtomicInteger();
        // 1回目の発見の後で、枠を 5/10 へ移し既存行を別の割当 c へ付け替える（ロック集合不一致を起こす）
        doAnswer(inv -> {
            if (calls.getAndIncrement() == 0) {
                moveSlot(s1[1], d("2026-05-10"));
                jdbc.update("UPDATE shift_budget_consumptions SET allocation_id = ? WHERE slot_id = ? AND status = 'PLANNED'",
                        c, s1[1]);
            }
            return null;
        }).when(hooks).afterRecordDiscovered(anyLong(), anyLong(), anyLong());

        publish(s1[0]);

        assertThat(calls.get()).as("不一致の後に再解決してやり直す").isGreaterThanOrEqualTo(2);
        Map<String, Object> live = jdbc.queryForMap("SELECT allocation_id, hourly_rate_snapshot, amount FROM "
                + "shift_budget_consumptions WHERE slot_id = ? AND status = 'PLANNED' AND deleted_at IS NULL", s1[1]);
        assertThat(((Number) live.get("allocation_id")).longValue()).isEqualTo(b);
        assertThat((BigDecimal) live.get("hourly_rate_snapshot")).as("再解決後の勤務日（5/10）の有効時給")
                .isEqualByComparingTo("1200");
        assertThat((BigDecimal) live.get("amount")).isEqualByComparingTo("9600");
    }

    @Test
    @DisplayName("試練141b AC67 ロック集合不一致が3回続けば失敗イベント（CONSUMPTION_RECORD）1件、生存行は二重にならない")
    void 試練141b_3回失敗で失敗イベント() throws Exception {
        allocation(d("2026-04-01"), d("2026-04-30"));
        Long y1 = allocation(d("2026-05-01"), d("2026-05-31"));
        Long team2 = newTeamInOrg();
        Long y2 = allocation(orgId, team2, fiscalYearId, categoryId, d("2026-04-01"), d("2026-05-31"));
        long[] s1 = worked(d("2026-04-10"), workerId);
        AtomicInteger calls = new AtomicInteger();
        // 発見のたびに既存行を「計上先でも発見済みでもない割当」（Y1／Y2 を交互）へ付け替え、毎回ロック集合不一致を起こす
        doAnswer(inv -> {
            int n = calls.incrementAndGet();
            jdbc.update("UPDATE shift_budget_consumptions SET allocation_id = ? WHERE slot_id = ? AND status = 'PLANNED'",
                    n % 2 == 1 ? y1 : y2, s1[1]);
            return null;
        }).when(hooks).afterRecordDiscovered(anyLong(), anyLong(), anyLong());
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM shift_budget_failed_events WHERE organization_id = ? "
                + "AND event_type = 'CONSUMPTION_RECORD'", Long.class, orgId);

        publish(s1[0]);

        assertThat(calls.get()).isEqualTo(3);
        long after = jdbc.queryForObject("SELECT COUNT(*) FROM shift_budget_failed_events WHERE organization_id = ? "
                + "AND event_type = 'CONSUMPTION_RECORD'", Long.class, orgId);
        assertThat(after - before).isEqualTo(1);
        assertThat(liveRows(s1[1], workerId)).isEqualTo(1);
    }

    // ───────────────────────── 試練144 AC71 ─────────────────────────

    @Test
    @DisplayName("試練144b AC71 再実行中に別期間が PREPARED → REDEFERRED＋新イベント用の遅延1行、その締め後に REPLAYED")
    void 試練144b_再延期() throws Exception {
        aprilMay();
        Long worker2 = worker(teamId, orgId);
        long[] s = worked(d("2026-04-10"), workerId);
        long slotB = addSlot(s[0], d("2026-05-10"), workerId);
        publish(s[0]);

        // 1回目の締め（04）は Prepare 後で止め、2回目（05）も Prepare 後で止める
        CountDownLatch reached04 = new CountDownLatch(1);
        CountDownLatch release04 = new CountDownLatch(1);
        CountDownLatch reached05 = new CountDownLatch(1);
        CountDownLatch release05 = new CountDownLatch(1);
        AtomicInteger prepared = new AtomicInteger();
        doAnswer(inv -> {
            int n = prepared.getAndIncrement();
            if (n == 0) {
                reached04.countDown();
                await(release04);
            } else if (n == 1) {
                reached05.countDown();
                await(release05);
            }
            return null;
        }).when(hooks).afterPrepared(anyLong(), any());
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch releaseClaim = new CountDownLatch(1);
        AtomicInteger claims = new AtomicInteger();
        doAnswer(inv -> {
            if (claims.getAndIncrement() == 0) {
                claimed.countDown();
                await(releaseClaim);
            }
            return null;
        }).when(hooks).afterDeferralClaimed(any(), anyString());

        today(d("2026-06-01"));
        CompletableFuture<Void> close04 = async(() -> close("2026-04"));
        awaitReached(reached04, close04, "04 の Prepare 後");
        assign(s[1], workerId, worker2);
        publish(s[0]);
        release04.countDown();
        awaitReached(claimed, close04, "04 の遅延再実行の確保後");
        CompletableFuture<Void> close05 = async(() -> close("2026-05"));
        awaitReached(reached05, close05, "05 の Prepare 後");
        releaseClaim.countDown();
        close04.get(60, TimeUnit.SECONDS);

        List<Map<String, Object>> rows = deferrals(s[0]);
        assertThat(rows).extracting(r -> r.get("state")).containsExactly("REDEFERRED", "PENDING");

        release05.countDown();
        close05.get(60, TimeUnit.SECONDS);
        assertThat(deferrals(s[0])).extracting(r -> r.get("state")).containsExactly("REDEFERRED", "REPLAYED");
        assertThat(liveStatus(slotB, workerId)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("試練144c AC71 通常の非同期入口（公開イベント AFTER_COMMIT）の記録件数は従来と同一（現状固定）")
    void 試練144c_非同期入口の記録件数() {
        aprilMay();
        Long worker2 = worker(teamId, orgId);
        long[] s = shift(teamId, d("2026-04-10"), LocalTime.of(9, 0), LocalTime.of(17, 0), workerId, worker2);
        tx.executeWithoutResult(t -> events.publishEvent(
                new ShiftPublishedEvent(s[0], teamId, adminId, LocalDateTime.now())));
        drainEventPool();
        assertThat(liveStatus(s[1], workerId)).isEqualTo("PLANNED");
        assertThat(liveStatus(s[1], worker2)).isEqualTo("PLANNED");
        assertThat(consumedAmount(allocationId)).isEqualByComparingTo("16000");
    }

    // ───────────────────────── 試練145 AC72 ─────────────────────────

    @Test
    @DisplayName("試練145a AC72 Prepare → 公開取消（延期）→ Abort で、回収が cancelAllForShift を呼び、印の外れた PLANNED を取消・減算し REPLAYED")
    void 試練145a_公開取消延期_Abort() throws Exception {
        aprilMay();
        long[] s1 = worked(d("2026-04-10"), workerId);
        PausedClose p = new PausedClose().start("2026-04");
        unpublish(s1[0]);
        assertThat(liveStatus(s1[1], workerId)).isEqualTo("PLANNED");
        setFiscalYearStatus(fiscalYearId, "CLOSED");
        p.finish();

        assertThat(liveStatus(s1[1], workerId)).isNull();
        assertThat(consumedAmount(allocationId)).isEqualByComparingTo("0");
        assertThat(deferrals(s1[0])).extracting(r -> r.get("state")).containsExactly("REPLAYED");
    }

    @Test
    @DisplayName("試練145b AC72 Prepare → 公開取消 → Commit で CONFIRMED 不変・CANCEL_AFTER_CLOSE 1件")
    void 試練145b_公開取消延期_Commit() throws Exception {
        aprilMay();
        long[] s1 = worked(d("2026-04-10"), workerId);
        PausedClose p = new PausedClose().start("2026-04");
        unpublish(s1[0]);
        p.finish();

        assertThat(liveStatus(s1[1], workerId)).isEqualTo("CONFIRMED");
        assertThat(failedEvents(orgId, "CANCEL_AFTER_CLOSE")).isEqualTo(1);
    }

    @Test
    @DisplayName("試練145c AC72 担当者追加の記録延期後にアーカイブ → 回収は取消側で新 PLANNED 0（Abort 後でも）")
    void 試練145c_追加延期後のアーカイブ() throws Exception {
        aprilMay();
        Long worker2 = worker(teamId, orgId);
        long[] s1 = worked(d("2026-04-10"), workerId);
        PausedClose p = new PausedClose().start("2026-04");
        assign(s1[1], workerId, worker2);
        publish(s1[0]);
        archive(s1[0]);
        setFiscalYearStatus(fiscalYearId, "CLOSED");
        p.finish();

        assertThat(liveRows(s1[1], worker2)).isZero();
        assertThat(liveRows(s1[1], workerId)).as("アーカイブ済みに PLANNED が残らない").isZero();
        assertCacheConsistent(allocationId);
    }

    @Test
    @DisplayName("試練145d AC72 公開取消（延期）→ Abort → 回収者が COLLECTING を読み取消側を選んだ後・取消 TX 前に再公開 → 取消 TX は PUBLISHED を読み整合取消のみ、PLANNED は残り重複なし")
    void 試練145d_取消側選択後の再公開() throws Exception {
        aprilMay();
        long[] s1 = worked(d("2026-04-10"), workerId);
        CountDownLatch chosen = new CountDownLatch(1);
        CountDownLatch releaseChosen = new CountDownLatch(1);
        doAnswer(inv -> {
            chosen.countDown();
            await(releaseChosen);
            return null;
        }).when(hooks).beforeReplayCancelTx(any(), anyLong());

        PausedClose p = new PausedClose().start("2026-04");
        unpublish(s1[0]);
        setFiscalYearStatus(fiscalYearId, "CLOSED");
        p.release.countDown();
        awaitReached(chosen, p.worker, "回収の取消 TX 前（beforeReplayCancelTx）");
        publish(s1[0]);
        releaseChosen.countDown();
        p.worker.get(60, TimeUnit.SECONDS);

        assertThat(liveRows(s1[1], workerId)).isEqualTo(1);
        assertThat(liveStatus(s1[1], workerId)).isEqualTo("PLANNED");
        assertThat(deferrals(s1[0])).extracting(r -> r.get("state")).containsExactly("REPLAYED");
        assertCacheConsistent(allocationId);
    }

    @Test
    @DisplayName("試練145e AC72 取消 TX が FOR SHARE を保持中の再公開 UPDATE は取消コミット後に進み、公開イベントで PLANNED が再記録される（PLANNED 1・消化額一致）")
    void 試練145e_FOR_SHARE保持中の再公開() throws Exception {
        aprilMay();
        long[] s1 = worked(d("2026-04-10"), workerId);
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        doAnswer(inv -> {
            read.countDown();
            await(releaseRead);
            return null;
        }).when(hooks).afterCancelScheduleRead(anyLong(), anyString());

        jdbc.update("UPDATE shift_schedules SET status = 'COLLECTING' WHERE id = ?", s1[0]);
        CompletableFuture<Void> cancel = async(() -> {
            cancelListener.onShiftScheduleClosed(new ShiftScheduleClosedEvent(s1[0], teamId, adminId,
                    ShiftScheduleCloseReason.UNPUBLISHED));
            drainEventPool();
        });
        awaitReached(read, cancel, "取消 TX の FOR SHARE 読取後（afterCancelScheduleRead）");
        CompletableFuture<Void> republish = async(() -> tx.executeWithoutResult(t ->
                jdbc.update("UPDATE shift_schedules SET status = 'PUBLISHED' WHERE id = ?", s1[0])));
        Thread.sleep(1000);
        assertThat(republish.isDone()).as("再公開 UPDATE は FOR SHARE を待つ").isFalse();
        releaseRead.countDown();
        cancel.get(60, TimeUnit.SECONDS);
        republish.get(60, TimeUnit.SECONDS);
        publish(s1[0]);

        assertThat(liveRows(s1[1], workerId)).isEqualTo(1);
        assertThat(consumedAmount(allocationId)).isEqualByComparingTo("8000");
        assertCacheConsistent(allocationId);
    }

    @Test
    @DisplayName("試練145f AC72 公開取消 → 取消リスナー遅延中に担当解除・再公開 → 取消は PUBLISHED を読み旧 PLANNED を整合取消（A 0）。B に差替えなら B 1、担当不変なら残る")
    void 試練145f_遅延取消の整合取消() throws Exception {
        aprilMay();
        Long workerB = worker(teamId, orgId);
        long[] gone = worked(d("2026-04-10"), workerId);
        long[] swapped = worked(d("2026-04-11"), workerId);
        long[] kept = worked(d("2026-04-12"), workerId);

        for (long[] s : List.of(gone, swapped, kept)) {
            jdbc.update("UPDATE shift_schedules SET status = 'COLLECTING' WHERE id = ?", s[0]);
        }
        assign(gone[1]);
        assign(swapped[1], workerB);
        for (long[] s : List.of(gone, swapped, kept)) {
            publish(s[0]);
        }
        for (long[] s : List.of(gone, swapped, kept)) {
            cancelListener.onShiftScheduleClosed(new ShiftScheduleClosedEvent(s[0], teamId, adminId,
                    ShiftScheduleCloseReason.UNPUBLISHED));
        }
        drainEventPool();

        assertThat(liveRows(gone[1], workerId)).as("全担当解除 → 旧 PLANNED 0").isZero();
        assertThat(liveRows(swapped[1], workerId)).isZero();
        assertThat(liveRows(swapped[1], workerB)).isEqualTo(1);
        assertThat(liveRows(kept[1], workerId)).as("担当不変は残り重複なし").isEqualTo(1);
        assertCacheConsistent(allocationId);
    }

    // ───────────────────────── 試練148 AC78 ─────────────────────────

    /** Commit 後の再実行を障害で止め、PENDING の遅延を1件作る。 */
    private UUID pendingDeferral(long[] s1, Long worker2) throws Exception {
        doAnswer(inv -> {
            throw new IllegalStateException("2152α: 初回再実行を止める");
        }).when(hooks).afterDeferralClaimed(any(), anyString());
        PausedClose p = new PausedClose().start("2026-04");
        assign(s1[1], workerId, worker2);
        publish(s1[0]);
        setFiscalYearStatus(fiscalYearId, "CLOSED");
        p.finish();
        setFiscalYearStatus(fiscalYearId, "OPEN");
        org.mockito.Mockito.reset(hooks);
        return pendingDeferralId(s1[0]);
    }

    private CompletableFuture<ReplayOutcome> pausedReplayOfA(UUID id, CountDownLatch claimedA, CountDownLatch releaseA) {
        doAnswer(inv -> {
            claimedA.countDown();
            await(releaseA);
            return null;
        }).when(hooks).afterDeferralClaimed(eq(id), eq("A"));
        return CompletableFuture.supplyAsync(() -> deferralService.replay(id, "A"));
    }

    @Test
    @DisplayName("試練148a AC78 A の確保コミット後・処理中に B が取り直す（リース有効）→ B は確保0行で終了、A は REPLAYED")
    void 試練148a_有効リース中は確保できない() throws Exception {
        aprilMay();
        Long worker2 = worker(teamId, orgId);
        long[] s1 = worked(d("2026-04-10"), workerId);
        UUID id = pendingDeferral(s1, worker2);
        CountDownLatch claimedA = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        CompletableFuture<ReplayOutcome> a = pausedReplayOfA(id, claimedA, releaseA);
        awaitReached(claimedA, a, "A の確保後");

        assertThat(deferralService.replay(id, "B")).isEqualTo(ReplayOutcome.NOT_CLAIMED);
        releaseA.countDown();

        assertThat(a.get(60, TimeUnit.SECONDS)).isEqualTo(ReplayOutcome.REPLAYED);
        assertThat(liveRows(s1[1], worker2)).isEqualTo(1);
    }

    @Test
    @DisplayName("試練148b AC78 A 停止 → リース切れ後に B が確保・完了、その後の A の確定は0行（DISCARDED）で結果1回分")
    void 試練148b_リース切れ後は別回収者() throws Exception {
        aprilMay();
        Long worker2 = worker(teamId, orgId);
        long[] s1 = worked(d("2026-04-10"), workerId);
        UUID id = pendingDeferral(s1, worker2);
        CountDownLatch claimedA = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        CompletableFuture<ReplayOutcome> a = pausedReplayOfA(id, claimedA, releaseA);
        awaitReached(claimedA, a, "A の確保後");
        jdbc.update("UPDATE shift_budget_record_deferrals SET lease_until = NOW() - INTERVAL 1 MINUTE WHERE shift_id = ?",
                s1[0]);

        assertThat(deferralService.replay(id, "B")).isEqualTo(ReplayOutcome.REPLAYED);
        releaseA.countDown();

        assertThat(a.get(60, TimeUnit.SECONDS)).isIn(ReplayOutcome.DISCARDED, ReplayOutcome.NOT_CLAIMED);
        assertThat(liveRows(s1[1], worker2)).as("結果は1回分").isEqualTo(1);
        assertThat(deferrals(s1[0])).extracting(r -> r.get("state")).containsExactly("REPLAYED");
        assertCacheConsistent(allocationId);
    }

    @Test
    @DisplayName("試練148c AC78 A の延長失敗（所有喪失）→ A は中断し、B 確保時も結果1回分")
    void 試練148c_延長失敗で中断() throws Exception {
        aprilMay();
        Long worker2 = worker(teamId, orgId);
        long[] s1 = worked(d("2026-04-10"), workerId);
        UUID id = pendingDeferral(s1, worker2);
        CountDownLatch claimedA = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        CompletableFuture<ReplayOutcome> a = pausedReplayOfA(id, claimedA, releaseA);
        awaitReached(claimedA, a, "A の確保後");
        jdbc.update("UPDATE shift_budget_record_deferrals SET lease_until = NOW() - INTERVAL 1 MINUTE WHERE shift_id = ?",
                s1[0]);
        CountDownLatch claimedB = new CountDownLatch(1);
        CountDownLatch releaseB = new CountDownLatch(1);
        doAnswer(inv -> {
            claimedB.countDown();
            await(releaseB);
            return null;
        }).when(hooks).afterDeferralClaimed(eq(id), eq("B"));
        CompletableFuture<ReplayOutcome> b = CompletableFuture.supplyAsync(() -> deferralService.replay(id, "B"));
        awaitReached(claimedB, b, "B の確保後");

        assertThat(deferralService.extendLease(id, "A")).as("所有喪失した A の延長は0行").isFalse();
        releaseA.countDown();
        assertThat(a.get(60, TimeUnit.SECONDS)).isIn(ReplayOutcome.DISCARDED, ReplayOutcome.NOT_CLAIMED);
        releaseB.countDown();
        assertThat(b.get(60, TimeUnit.SECONDS)).isEqualTo(ReplayOutcome.REPLAYED);
        assertThat(liveRows(s1[1], worker2)).isEqualTo(1);
        assertCacheConsistent(allocationId);
    }
}
