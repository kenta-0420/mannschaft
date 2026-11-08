package com.mannschaft.app.shift;

import com.mannschaft.app.auth.service.UserStatusLookupService;
import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import com.mannschaft.app.shift.dto.SlotAssignmentMoveRequest;
import com.mannschaft.app.shift.dto.SlotAssignmentPatchRequest;
import com.mannschaft.app.shift.dto.UpdateShiftSlotRequest;
import com.mannschaft.app.shift.entity.ShiftSwapRequestEntity;
import com.mannschaft.app.shift.repository.ShiftAssignmentRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.shift.repository.ShiftSwapRequestRepository;
import com.mannschaft.app.shift.service.ShiftScheduleService;
import com.mannschaft.app.shift.service.ShiftSlotService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

/**
 * CMP-261021-0648: 承認の実TX原子性と現在読み取りを実MySQLで検査する。
 *
 * <p>spyは実Repositoryの保存・flush後の故障点注入と、実ユーザー資格照会後の対象変更同期だけに限定する。
 * DB・Security・業務処理自体の代替はしない。競合は親ロック保持とSQL/実スレッドstackで到達を観測し、
 * CountDownLatchで順序を固定する。sleep、共有DB、テスト全体の自動rollbackは使用しない。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("CMP-261021-0648 承認の原子性と競合の実MySQL契約")
class ShiftSwapApprovalAtomicityConcurrencyIT extends ShiftSwapApprovalTestSupport {
    @Autowired private ShiftSlotService slotService;
    @Autowired private ShiftScheduleService scheduleService;
    @MockitoSpyBean private ShiftSlotRepository faultSlots;
    @MockitoSpyBean private ShiftAssignmentRepository faultAssignments;
    @MockitoSpyBean private ShiftSwapRequestRepository faultSwaps;
    @MockitoSpyBean private UserStatusLookupService actualUserStatus;

    private enum FailurePoint { SLOT_FLUSH, HISTORY_SAVE, SWAP_SAVE }

    @ParameterizedTest(name = "AC11_実保存後rollback [{index}] {arguments}")
    @EnumSource(FailurePoint.class)
    void AC11_実保存途中の例外はswap担当version履歴の全列をrollbackする(FailurePoint point) throws Exception {
        Long slot = slot(List.of(a, c), ShiftScheduleStatus.PUBLISHED);
        history(slot, a, ShiftAssignmentStatus.CONFIRMED, null);
        Long swap = accepted(slot, a, b);
        Map<String, String> before = snapshot();
        AtomicBoolean reached = new AtomicBoolean();
        AtomicBoolean assignmentWasWritten = new AtomicBoolean();
        AtomicBoolean historyWasWritten = new AtomicBoolean();
        Answer<?> realSlots = mockingDetails(faultSlots).getMockCreationSettings().getDefaultAnswer();
        Answer<?> realHistory = mockingDetails(faultAssignments).getMockCreationSettings().getDefaultAnswer();
        Answer<?> realSwaps = mockingDetails(faultSwaps).getMockCreationSettings().getDefaultAnswer();
        Runnable fail = () -> {
            em.flush();
            assignmentWasWritten.set(ShiftAssignedUserIds.parse((String) em.createNativeQuery(
                            "SELECT assigned_user_ids FROM shift_slots WHERE id=:id")
                    .setParameter("id", slot).getSingleResult()).equals(List.of(b, c)));
            historyWasWritten.set(((Number) em.createNativeQuery("SELECT COUNT(*) FROM shift_assignments "
                            + "WHERE slot_id=:slot AND user_id=:user AND status='CONFIRMED'")
                    .setParameter("slot", slot).setParameter("user", b).getSingleResult()).longValue() == 1);
            reached.set(true);
            throw new IllegalStateException("0648試練の保存後障害: " + point);
        };
        if (point == FailurePoint.SLOT_FLUSH) {
            doAnswer(invocation -> { realSlots.answer(invocation); fail.run(); return null; })
                    .when(faultSlots).flush();
        } else if (point == FailurePoint.HISTORY_SAVE) {
            doAnswer(invocation -> { Object result = realHistory.answer(invocation); fail.run(); return result; })
                    .when(faultAssignments).saveAll(any());
        } else {
            doAnswer(invocation -> { Object result = realSwaps.answer(invocation); fail.run(); return result; })
                    .when(faultSwaps).save(any(ShiftSwapRequestEntity.class));
        }

        var response = resolve(admin, swap, "APPROVE");

        expect(response, 500, "COMMON_999");
        assertThat(reached).isTrue();
        assertThat(assignmentWasWritten).as("故障前に同TXでslot差替が実DBへflushされる").isTrue();
        if (point != FailurePoint.SLOT_FLUSH) {
            assertThat(historyWasWritten).as("保存済み履歴も同TXに参加する").isTrue();
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void AC12_同一依頼を同時承認しても成功一回で担当と履歴も一回だけ() throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.PUBLISHED);
        Long swap = accepted(slot, a, b);
        var results = parallelBehindParent(slot, () -> resolve(admin, swap, "APPROVE"),
                () -> resolve(admin, swap, "APPROVE"));
        assertOneSuccessAndConflict(results);
        assertThat(assigned(slot)).containsExactly(b);
        assertNewConfirmed(slot, b);
        assertThat(inTx(() -> assignments.findAllBySlotId(slot))).hasSize(1);
    }

    @Test
    void AC12_承認と却下の競合は終状態と担当の組合せが一致する() throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.PUBLISHED);
        Long swap = accepted(slot, a, b);
        var results = parallelBehindParent(slot, () -> resolve(admin, swap, "APPROVE"),
                () -> resolve(admin, swap, "REJECT"));
        assertOneSuccessAndConflict(results);
        if (swapState(swap).equals("APPROVED")) {
            assertThat(assigned(slot)).containsExactly(b);
            assertNewConfirmed(slot, b);
        } else {
            assertThat(swapState(swap)).isEqualTo("REJECTED");
            assertThat(assigned(slot)).containsExactly(a);
            assertThat(inTx(() -> assignments.findAllBySlotId(slot))).isEmpty();
        }
    }

    @Test
    void AC12_ACCEPTED取消との競合でも取消409と承認後の担当が整合する() throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.PUBLISHED);
        Long swap = accepted(slot, a, b);
        var results = parallelBehindParent(slot, () -> resolve(admin, swap, "APPROVE"),
                () -> mockMvc.perform(delete("/api/v1/shifts/swap-requests/{id}", swap)
                        .header("Authorization", bearer(a))).andReturn().getResponse());
        assertOneSuccessAndConflict(results);
        assertThat(swapState(swap)).isEqualTo("APPROVED");
        assertThat(assigned(slot)).containsExactly(b);
    }

    @Test
    void AC13_同枠同申請者の別依頼を同時承認しても一件だけ成功する() throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.PUBLISHED);
        Long one = accepted(slot, a, b);
        Long two = accepted(slot, a, c);
        var results = parallelBehindParent(slot, () -> resolve(admin, one, "APPROVE"),
                () -> resolve(admin, two, "APPROVE"));
        assertOneSuccessAndConflict(results);
        Long winner = swapState(one).equals("APPROVED") ? b : c;
        assertThat(List.of(swapState(one), swapState(two))).containsExactlyInAnyOrder("APPROVED", "ACCEPTED");
        assertThat(assigned(slot)).containsExactly(winner);
        assertNewConfirmed(slot, winner);
        assertThat(inTx(() -> assignments.findAllBySlotId(slot))).hasSize(1);
    }

    private enum Change { UNPUBLISH, PATCH_KEEP_SOURCE, MOVE_SOURCE, UPDATE_SLOT, DELETE_SLOT, DELETE_PARENT, THIRD_DUPLICATE }

    @ParameterizedTest(name = "AC08_09_14_先commit変更 [{index}] {arguments}")
    @EnumSource(Change.class)
    void AC08_09_14_先commitの親枠手動変更を現在値で判定し古いPCで上書きしない(Change change) throws Exception {
        Long slot = slot(List.of(c, a), ShiftScheduleStatus.PUBLISHED);
        Long schedule = scheduleOf(slot);
        Long swap = accepted(slot, a, b);
        Long extra = extraSlot(schedule, List.of(),
                change == Change.THIRD_DUPLICATE ? LocalTime.of(9, 0) : LocalTime.of(19, 0),
                change == Change.THIRD_DUPLICATE ? LocalTime.of(18, 0) : LocalTime.of(21, 0));
        CountDownLatch changed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Map<String, String>> afterChange = new AtomicReference<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var writer = executor.submit(() -> inTx(() -> {
                // status APIは通常読込→save。flushが取る行ロックを保持し、架空のFOR UPDATEを仮定しない。
                if (change == Change.UNPUBLISH) {
                    scheduleService.transitionStatus(schedule, "ADJUSTING", admin);
                } else {
                    schedules.findByIdForUpdate(schedule).orElseThrow();
                    applyChange(change, slot, extra);
                }
                em.flush();
                afterChange.set(snapshot());
                changed.countDown();
                await(release);
                return null;
            }));
            try {
                assertThat(changed.await(20, TimeUnit.SECONDS)).isTrue();
                SqlIntentCounter.reset();
                AtomicReference<Thread> thread = new AtomicReference<>();
                var request = executor.submit(() -> { thread.set(Thread.currentThread()); return resolve(admin, swap, "APPROVE"); });
                awaitParentOrCompletion(request, thread);
                release.countDown();
                writer.get(20, TimeUnit.SECONDS);
                var response = request.get(20, TimeUnit.SECONDS);
                if (change == Change.PATCH_KEEP_SOURCE || change == Change.UPDATE_SLOT) {
                    expect(response, 200, null);
                    assertThat(assigned(slot)).containsExactly(c, b, d);
                    if (change == Change.UPDATE_SLOT) {
                        assertThat(inTx(() -> slots.findById(slot).orElseThrow().getNote())).isEqualTo("先行編集");
                        assertThat(inTx(() -> slots.findById(slot).orElseThrow().getStartTime())).isEqualTo(LocalTime.of(10, 0));
                    }
                } else {
                    String code = switch (change) {
                        case UNPUBLISH -> "SHIFT_012";
                        case MOVE_SOURCE -> "SHIFT_018";
                        case DELETE_SLOT -> "SHIFT_002";
                        case DELETE_PARENT -> "SHIFT_001";
                        case THIRD_DUPLICATE -> "SHIFT_042";
                        default -> throw new IllegalStateException();
                    };
                    expect(response, change == Change.DELETE_SLOT || change == Change.DELETE_PARENT ? 404 : 409, code);
                    assertThat(snapshot()).isEqualTo(afterChange.get());
                }
            } finally {
                release.countDown();
                writer.get(20, TimeUnit.SECONDS);
            }
        }
    }

    private void applyChange(Change change, Long slot, Long extra) {
        switch (change) {
            case PATCH_KEEP_SOURCE -> slotService.patchSlotAssignments(slot,
                    new SlotAssignmentPatchRequest(List.of(d), List.of(), 0), admin);
            case MOVE_SOURCE -> slotService.moveAssignment(slot,
                    new SlotAssignmentMoveRequest(extra, a, 0L, 0L), admin);
            case UPDATE_SLOT -> slotService.updateSlot(slot, new UpdateShiftSlotRequest(null,
                    LocalTime.of(10, 0), null, null, null, List.of(c, a, d), "先行編集"), admin);
            case DELETE_SLOT -> slotService.deleteSlot(slot);
            case DELETE_PARENT -> scheduleService.deleteSchedule(scheduleOf(slot), admin);
            case THIRD_DUPLICATE -> slotService.patchSlotAssignments(extra,
                    new SlotAssignmentPatchRequest(List.of(b), List.of(), 0), admin);
            default -> throw new IllegalStateException();
        }
    }

    @Test
    void AC15_他テナントの親ロックを認可前に要求しない() throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.PUBLISHED);
        Long swap = accepted(slot, a, b);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var before = snapshot();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> inTx(() -> {
                schedules.findByIdForUpdate(scheduleOf(slot)).orElseThrow();
                locked.countDown(); await(release); return null;
            }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                var denied = executor.submit(() -> resolve(actors.get("OTHER_TENANT_ADMIN"), swap, "APPROVE"));
                expect(denied.get(10, TimeUnit.SECONDS), 404, "SHIFT_005");
                assertThat(snapshot()).isEqualTo(before);
            } finally {
                release.countDown(); holder.get(20, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void AC16_実資格確認後に承認対象が変われば未確認の別人へ差替しない() throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.PUBLISHED);
        Long swap = accepted(slot, a, b);
        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Answer<?> real = mockingDetails(actualUserStatus).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object result = real.answer(invocation);
            checked.countDown(); await(release);
            return result;
        }).when(actualUserStatus).isActive(b);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var request = executor.submit(() -> resolve(admin, swap, "APPROVE"));
            try {
                Awaitility.await().atMost(20, TimeUnit.SECONDS).until(() -> checked.getCount() == 0 || request.isDone());
                if (checked.getCount() == 0) {
                    inTx(() -> {
                        swaps.findById(swap).orElseThrow().accept(c);
                        return null;
                    });
                }
                var before = snapshot();
                release.countDown();
                expect(request.get(20, TimeUnit.SECONDS), 409, "SHIFT_018");
                assertThat(snapshot()).isEqualTo(before);
            } finally {
                release.countDown();
            }
        }
    }

    private List<MockHttpServletResponse> parallelBehindParent(Long slot,
            Callable<MockHttpServletResponse> one, Callable<MockHttpServletResponse> two) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            var holder = executor.submit(() -> inTx(() -> {
                schedules.findByIdForUpdate(scheduleOf(slot)).orElseThrow();
                locked.countDown(); await(release); return null;
            }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                SqlIntentCounter.reset();
                AtomicReference<Thread> firstThread = new AtomicReference<>();
                AtomicReference<Thread> secondThread = new AtomicReference<>();
                var first = executor.submit(() -> { firstThread.set(Thread.currentThread()); return one.call(); });
                var second = executor.submit(() -> { secondThread.set(Thread.currentThread()); return two.call(); });
                awaitParentOrCompletion(first, firstThread);
                awaitParentOrCompletion(second, secondThread);
                release.countDown();
                holder.get(20, TimeUnit.SECONDS);
                return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            } finally {
                release.countDown(); holder.get(20, TimeUnit.SECONDS);
            }
        }
    }

    private void assertOneSuccessAndConflict(List<MockHttpServletResponse> responses) throws Exception {
        assertThat(responses.stream().map(MockHttpServletResponse::getStatus).toList())
                .containsExactlyInAnyOrder(200, 409);
        for (var response : responses) {
            if (response.getStatus() == 409) {
                assertThat(json(response).at("/error/code").asText())
                        .isIn("SHIFT_013", "SHIFT_018", "COMMON_003");
            }
        }
    }

    private static void awaitParentOrCompletion(Future<?> request, AtomicReference<Thread> thread) {
        Awaitility.await().atMost(20, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(20))
                .until(() -> request.isDone() || isInsideParentLock(thread.get()));
    }

    private static boolean isInsideParentLock(Thread thread) {
        return thread != null && Arrays.stream(thread.getStackTrace())
                .anyMatch(frame -> frame.getMethodName().equals("findByIdForUpdate"))
                && SqlIntentCounter.capturedSqls().stream().anyMatch(sql -> sql.contains("from shift_schedules ")
                        && sql.toLowerCase(java.util.Locale.ROOT).contains("for update"));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("0648競合試験の同期が期限を超えました");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }
}
