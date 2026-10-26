package com.mannschaft.app.shift;

import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import com.mannschaft.app.shift.dto.CreateSwapRequestRequest;
import com.mannschaft.app.shift.entity.ShiftSwapRequestEntity;
import com.mannschaft.app.shift.service.ShiftSwapService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-261021-0649: ShiftSwapController#createSwapRequest の未決重複を実JWT・実Security・実MySQLで検査する。
 *
 * <p>同slot/requesterのPENDING・ACCEPTED拒否と古いRR snapshotでの並行作成が試練のred対象。
 * terminal後の再提出・既存資格・DTO境界・rollbackは既存契約の回帰固定である。
 * 0648の所有fixtureと全列snapshotを再利用し、実BeanやDBをmock/spyへ置換しない。
 * テスト全体を自動rollbackせず、親ロック到達を観測したlatchで実TXの順序を固定する。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261021-0649 交代依頼の未決重複・現在読取契約")
class ShiftSwapCreationDuplicateContractIT extends ShiftSwapApprovalTestSupport {

    private static final int WAIT_SECONDS = 30;
    private static final int REASON_LIMIT = 500;
    @Autowired private ShiftSwapService service;

    @ParameterizedTest
    @CsvSource({"PENDING,false", "PENDING,true", "ACCEPTED,false", "ACCEPTED,true"})
    void AC02_03_04_未決は宛先順序理由モードを変えても409で全列不変(
            SwapRequestStatus state, boolean existingOpenCall) throws Exception {
        Long slot = slot(List.of(a, c), ShiftScheduleStatus.PUBLISHED);
        inTx(() -> swaps.save(ShiftSwapRequestEntity.builder().slotId(slot).requesterId(a).accepterId(b)
                .status(state).reason("元理由").isOpenCall(existingOpenCall)
                .recipientMode(existingOpenCall ? "OPEN_CALL" : "SPECIFIC")
                .targetUserIds(existingOpenCall ? null : ShiftAssignedUserIds.serialize(List.of(b, d))).build()));
        var before = snapshot();
        List<Map<String, Object>> attempts = List.of(
                body(slot, false, "元理由", List.of(b, d)),
                body(slot, false, "元理由", List.of(d, b)),
                body(slot, false, "元理由", List.of(c)),
                body(slot, false, "別理由", List.of(b, d)),
                body(slot, true, null, null));

        for (var request : attempts) {
            expect(create(a, request), 409, "SHIFT_013");
            assertThat(snapshot()).as("既存受諾者・担当・履歴・versionも更新しない").isEqualTo(before);
        }
    }

    @Test
    void AC05_同枠の別担当者と同一担当の別枠は201() throws Exception {
        Long slot = slot(List.of(a, c), ShiftScheduleStatus.PUBLISHED);
        swap(slot, a, b, SwapRequestStatus.PENDING);
        Long otherSlot = extraSlot(scheduleOf(slot), List.of(a), LocalTime.of(19, 0), LocalTime.of(21, 0));

        expect(create(c, body(slot, true, "別担当", null)), 201, null);
        expect(create(a, body(otherSlot, false, "別枠", List.of(b))), 201, null);

        assertThat(pendingCount(slot)).isEqualTo(2L);
        assertThat(pendingCount(otherSlot)).isEqualTo(1L);
    }

    @ParameterizedTest
    @EnumSource(value = SwapRequestStatus.class, names = {"APPROVED", "REJECTED", "CANCELLED"})
    void AC06_terminal履歴だけなら再提出201で宛先理由を保存する(SwapRequestStatus state) throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.PUBLISHED);
        Long old = swap(slot, a, b, state);

        var response = create(a, body(slot, false, "再提出理由", List.of(b, d)));

        expect(response, 201, null);
        Long created = json(response).at("/data/id").longValue();
        inTx(() -> {
            var stored = swaps.findById(created).orElseThrow();
            assertThat(stored.getRequesterId()).isEqualTo(a);
            assertThat(stored.getSlotId()).isEqualTo(slot);
            assertThat(stored.getReason()).isEqualTo("再提出理由");
            assertThat(ShiftAssignedUserIds.parse(stored.getTargetUserIds())).containsExactly(b, d);
            assertThat(swaps.findById(old).orElseThrow().getStatus()).isEqualTo(state);
            return null;
        });
    }

    @Test
    void AC06_承認後の元担当は403で正当に再割当された後は201() throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.PUBLISHED);
        Long accepted = accepted(slot, a, b);
        expect(resolve(admin, accepted, "APPROVE"), 200, null);
        var afterApproval = snapshot();

        expect(create(a, body(slot, false, null, null)), 403, "SHIFT_019");
        assertThat(snapshot()).isEqualTo(afterApproval);
        inTx(() -> {
            schedules.findByIdForUpdate(scheduleOf(slot)).orElseThrow();
            slots.findById(slot).orElseThrow().updateAssignedUserIds(ShiftAssignedUserIds.serialize(List.of(a)));
            return null;
        });

        expect(create(a, body(slot, false, "再割当後", null)), 201, null);
    }

    @Test
    void AC07_既存未決が複数でも修復せず新規だけ409() throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.PUBLISHED);
        swap(slot, a, b, SwapRequestStatus.PENDING);
        accepted(slot, a, d);
        var before = snapshot();

        expect(create(a, body(slot, true, "再送", null)), 409, "SHIFT_013");

        assertThat(snapshot()).isEqualTo(before);
        assertThat(pendingCount(slot)).isEqualTo(2L);
    }

    @Test
    void AC08_両作成TXが古snapshotを持って親lock待ちしても201と409が各一回() throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.PUBLISHED);
        var request = body(slot, false, "同時送信", List.of(b));

        var responses = behindParent(slot, () -> create(a, request), () -> create(a, request));

        assertThat(responses.stream().map(MockHttpServletResponse::getStatus).toList())
                .containsExactlyInAnyOrder(201, 409);
        for (var response : responses) {
            if (response.getStatus() == 409) {
                expect(response, 409, "SHIFT_013");
            }
        }
        assertThat(pendingCount(slot)).isEqualTo(1L);
        assertThat(assigned(slot)).containsExactly(a);
        assertThat(historyRows()).isEqualTo("[]");
    }

    @Test
    void AC09_同枠別担当者の同時依頼は両方201() throws Exception {
        Long slot = slot(List.of(a, c), ShiftScheduleStatus.PUBLISHED);

        var responses = behindParent(slot, () -> create(a, body(slot, true, null, null)),
                () -> create(c, body(slot, false, null, List.of(b))));

        assertThat(responses.stream().map(MockHttpServletResponse::getStatus).toList()).containsOnly(201).hasSize(2);
        assertThat(pendingCount(slot)).isEqualTo(2L);
    }

    @Test
    void AC09_別scheduleの親lock保持中も他方を作成できる() throws Exception {
        Long lockedSlot = slot(List.of(a), ShiftScheduleStatus.PUBLISHED);
        Long otherSlot = slot(List.of(a), ShiftScheduleStatus.PUBLISHED);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Long parent = scheduleOf(lockedSlot);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> inTx(() -> {
                schedules.findByIdForUpdate(parent).orElseThrow();
                locked.countDown();
                await(release);
                return null;
            }));
            try {
                assertThat(locked.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
                var result = executor.submit(() -> create(a, body(otherSlot, true, null, null)));
                expect(result.get(WAIT_SECONDS, TimeUnit.SECONDS), 201, null);
                assertThat(pendingCount(lockedSlot)).isZero();
                assertThat(pendingCount(otherSlot)).isEqualTo(1L);
            } finally {
                release.countDown();
                holder.get(WAIT_SECONDS, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void AC10_実保存後rollbackした先行要求を待機側は未決と数えず201() throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.PUBLISHED);
        CountDownLatch saved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var rolledBack = executor.submit(() -> new TransactionTemplate(txManager).execute(tx -> {
                expectCreatedInTransaction(a, slot);
                em.flush();
                assertThat(pendingCount(slot)).isEqualTo(1L);
                saved.countDown();
                await(release);
                tx.setRollbackOnly();
                return null;
            }));
            try {
                assertThat(saved.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
                AtomicReference<Thread> thread = new AtomicReference<>();
                var waiting = executor.submit(() -> {
                    thread.set(Thread.currentThread());
                    return create(a, body(slot, true, "rollback後", null));
                });
                awaitParent(waiting, thread);
                release.countDown();
                rolledBack.get(WAIT_SECONDS, TimeUnit.SECONDS);
                expect(waiting.get(WAIT_SECONDS, TimeUnit.SECONDS), 201, null);
                assertThat(pendingCount(slot)).isEqualTo(1L);
                assertThat(historyRows()).isEqualTo("[]");
            } finally {
                release.countDown();
                rolledBack.get(WAIT_SECONDS, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void AC10_実DB保存失敗で中途行を残さず次の正常HTTP要求は201() throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.PUBLISHED);
        var before = snapshot();

        // HTTPの501文字はDTOで拒否されるため、保存層の故障は実Serviceに列長超過を渡して発生させる。
        assertThatThrownBy(() -> inTx(() -> {
            service.createSwapRequest(new CreateSwapRequestRequest(slot, "x".repeat(REASON_LIMIT + 1), false, null), a);
            em.flush();
            return null;
        })).isInstanceOf(RuntimeException.class).hasRootCauseInstanceOf(java.sql.SQLException.class);

        assertThat(snapshot()).isEqualTo(before);
        expect(create(a, body(slot, false, "正常再試行", null)), 201, null);
        assertThat(pendingCount(slot)).isEqualTo(1L);
    }

    @Test
    void AC11_許可ロール全員が本人担当でも重複409() throws Exception {
        for (Long actor : List.of(a, admin, actors.get("DEPUTY_ADMIN"), actors.get("SYSTEM_ADMIN"))) {
            Long slot = slot(List.of(actor), ShiftScheduleStatus.PUBLISHED);
            swap(slot, actor, b, SwapRequestStatus.PENDING);
            var before = snapshot();
            expect(create(actor, body(slot, false, null, null)), 409, "SHIFT_013");
            assertThat(snapshot()).isEqualTo(before);
        }
    }

    @Test
    void AC11_未決があっても非公開と非本人担当は資格エラーを優先する() throws Exception {
        Long unpublished = slot(List.of(a), ShiftScheduleStatus.ADJUSTING);
        Long unassigned = slot(List.of(c), ShiftScheduleStatus.PUBLISHED);
        for (Long slot : List.of(unpublished, unassigned)) {
            swap(slot, a, b, SwapRequestStatus.PENDING);
        }
        var before = snapshot();

        expect(create(a, body(unpublished, true, null, null)), 409, "SHIFT_012");
        expect(create(a, body(unassigned, true, null, null)), 403, "SHIFT_019");

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void AC12_重複があっても認可失敗は追加lockや重複照会へ到達しない() throws Exception {
        Long slot = slot(List.of(a, actors.get("SUPPORTER")), ShiftScheduleStatus.PUBLISHED);
        swap(slot, a, b, SwapRequestStatus.PENDING);
        var before = snapshot();
        for (String role : List.of("OTHER_TEAM_ADMIN", "OTHER_TENANT_ADMIN")) {
            Long actor = actors.get(role);
            var absent = create(actor, body(Long.MAX_VALUE, true, null, null));
            expect(absent, 404, "SHIFT_002");
            try (var capture = SqlIntentCounter.captureCurrentThread()) {
                var denied = create(actor, body(slot, true, null, null));
                expect(denied, 404, "SHIFT_002");
                assertThat(denied.getContentAsString()).isEqualTo(absent.getContentAsString());
                assertNoWriteLockOrDuplicateRead(capture.capturedSqls());
            }
        }
        try (var capture = SqlIntentCounter.captureCurrentThread()) {
            expect(create(null, body(slot, true, null, null)), 401, null);
            expect(create(actors.get("SUPPORTER"), body(slot, true, null, null)), 403, "COMMON_002");
            assertNoWriteLockOrDuplicateRead(capture.capturedSqls());
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, REASON_LIMIT})
    void AC13_reason境界と宛先nullの後方互換は201(Integer length) throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.PUBLISHED);
        String reason = length == null ? null : "あ".repeat(length);
        var response = create(a, body(slot, false, reason, null));
        expect(response, 201, null);
        assertThat(inTx(() -> swaps.findById(jsonId(response)).orElseThrow().getReason())).isEqualTo(reason);
    }

    @Test
    void AC13_不正JSON必須slot理由超過と空宛先は400で全列不変() throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.PUBLISHED);
        var before = snapshot();
        for (String invalid : List.of("{", "{}", "{\"slotId\":null}",
                mapper.writeValueAsString(body(slot, false, "x".repeat(REASON_LIMIT + 1), null)),
                mapper.writeValueAsString(body(slot, false, null, List.of())))) {
            expect(createJson(a, invalid), 400, "COMMON_001");
            assertThat(snapshot()).isEqualTo(before);
        }
        for (Long absent : List.of(0L, -1L, Long.MAX_VALUE)) {
            expect(create(a, body(absent, false, null, null)), 404, "SHIFT_002");
            assertThat(snapshot()).isEqualTo(before);
        }
    }

    @Test
    void AC16_成功応答後に通信断を想定して再送しても新規行を増やさない() throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.PUBLISHED);
        var request = body(slot, true, "再送", null);
        expect(create(a, request), 201, null);
        var before = snapshot();

        expect(create(a, request), 409, "SHIFT_013");

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void AC18_履歴が多くても重複判定は現在読取の上限一行一SQL() throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.PUBLISHED);
        inTx(() -> {
            for (int i = 0; i < 40; i++) {
                swaps.save(ShiftSwapRequestEntity.builder().slotId(slot).requesterId(a)
                        .status(SwapRequestStatus.CANCELLED).reason("過去履歴").build());
            }
            return null;
        });
        try (var capture = SqlIntentCounter.captureCurrentThread()) {
            expect(create(a, body(slot, true, null, null)), 201, null);
            var sqls = capture.capturedSqls().stream().map(sql -> sql.toLowerCase(Locale.ROOT)).toList();
            var duplicateReads = sqls.stream().filter(sql -> sql.contains("from shift_swap_requests ")).toList();
            assertThat(duplicateReads).hasSize(1);
            String read = duplicateReads.getFirst();
            assertThat(read).contains("slot_id", "requester_id", "status");
            assertThat(isLocking(read)).as("RR snapshotでなく現在読取").isTrue();
            assertThat(read).as("全履歴を読み込まずSQLで最大1行に制限").containsPattern("limit\\s+\\?|fetch first\\s+\\?\\s+rows");
            int parent = firstIndex(sqls, "from shift_schedules ", "for update");
            int currentSlot = firstIndex(sqls, "from shift_slots ", "for share", "lock in share mode");
            int duplicate = sqls.indexOf(read);
            assertThat(parent).isGreaterThanOrEqualTo(0);
            assertThat(currentSlot).isGreaterThan(parent);
            assertThat(duplicate).isGreaterThan(currentSlot);
        }
    }

    private List<MockHttpServletResponse> behindParent(Long slot, Callable<MockHttpServletResponse> first,
                                                      Callable<MockHttpServletResponse> second) throws Exception {
        Long parent = scheduleOf(slot);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            var holder = executor.submit(() -> inTx(() -> {
                schedules.findByIdForUpdate(parent).orElseThrow();
                locked.countDown();
                await(release);
                return null;
            }));
            try {
                assertThat(locked.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
                SqlIntentCounter.reset();
                AtomicReference<Thread> oneThread = new AtomicReference<>();
                AtomicReference<Thread> twoThread = new AtomicReference<>();
                var one = executor.submit(() -> { oneThread.set(Thread.currentThread()); return first.call(); });
                var two = executor.submit(() -> { twoThread.set(Thread.currentThread()); return second.call(); });
                // Serviceはslotのplain read後に親lockを取る。両stackの到達を確認してRR snapshotを先に形成させる。
                awaitParent(one, oneThread);
                awaitParent(two, twoThread);
                assertThat(SqlIntentCounter.capturedSqls().stream().filter(sql -> sql.contains("from shift_slots ")).count())
                        .as("両要求のFacade読取と作成TX初回読取").isGreaterThanOrEqualTo(4L);
                release.countDown();
                holder.get(WAIT_SECONDS, TimeUnit.SECONDS);
                return List.of(one.get(WAIT_SECONDS, TimeUnit.SECONDS), two.get(WAIT_SECONDS, TimeUnit.SECONDS));
            } finally {
                release.countDown();
                holder.get(WAIT_SECONDS, TimeUnit.SECONDS);
            }
        }
    }

    private static void awaitParent(Future<?> request, AtomicReference<Thread> thread) {
        Awaitility.await().atMost(Duration.ofSeconds(WAIT_SECONDS)).pollInterval(Duration.ofMillis(20))
                .until(() -> request.isDone() || insideCreationParentLock(thread.get()));
        assertThat(request.isDone()).as("親lock保持中に要求が完了しない").isFalse();
        assertThat(insideCreationParentLock(thread.get())).isTrue();
    }

    private static boolean insideCreationParentLock(Thread thread) {
        if (thread == null) { return false; }
        var stack = Arrays.asList(thread.getStackTrace());
        return stack.stream().anyMatch(frame -> frame.getMethodName().equals("findByIdForUpdate"))
                && stack.stream().anyMatch(frame -> frame.getClassName().equals(ShiftSwapService.class.getName())
                        && frame.getMethodName().equals("createSwapRequest"));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("0649試練の同期が期限を超えました");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    private void expectCreatedInTransaction(Long actor, Long slot) {
        try {
            expect(create(actor, body(slot, false, "rollback対象", null)), 201, null);
        } catch (Exception error) {
            throw new IllegalStateException("実HTTP作成に失敗", error);
        }
    }

    private long pendingCount(Long slot) {
        return inTx(() -> ((Number) em.createNativeQuery("SELECT COUNT(*) FROM shift_swap_requests "
                        + "WHERE slot_id=:slot AND status IN ('PENDING','ACCEPTED')")
                .setParameter("slot", slot).getSingleResult()).longValue());
    }

    private Long jsonId(MockHttpServletResponse response) {
        try {
            return json(response).at("/data/id").longValue();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static Map<String, Object> body(Long slot, boolean openCall, String reason, List<Long> targets) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("slotId", slot);
        result.put("openCall", openCall);
        result.put("reason", reason);
        result.put("targetUserIds", targets);
        return result;
    }

    private MockHttpServletResponse create(Long actor, Map<String, Object> body) throws Exception {
        return createJson(actor, mapper.writeValueAsString(body));
    }

    private MockHttpServletResponse createJson(Long actor, String body) throws Exception {
        var request = post("/api/v1/shifts/swap-requests").header("Accept-Language", "ja")
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (actor != null) { request.header("Authorization", bearer(actor)); }
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private static boolean isLocking(String sql) {
        String lower = sql.toLowerCase(Locale.ROOT);
        return lower.contains("for update") || lower.contains("for share") || lower.contains("lock in share mode");
    }

    private static void assertNoWriteLockOrDuplicateRead(List<String> sqls) {
        assertThat(sqls).noneMatch(ShiftSwapCreationDuplicateContractIT::isLocking)
                .noneMatch(sql -> sql.toLowerCase(Locale.ROOT).contains("from shift_swap_requests "));
    }

    private static int firstIndex(List<String> sqls, String table, String... locks) {
        for (int i = 0; i < sqls.size(); i++) {
            String sql = sqls.get(i);
            if (sql.contains(table) && Arrays.stream(locks).anyMatch(sql::contains)) { return i; }
        }
        return -1;
    }
}
