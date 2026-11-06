package com.mannschaft.app.reservation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.aopalliance.intercept.MethodInterceptor;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-261104-0334 臨時休業通知送信の冪等性（{@code Idempotency-Key}）の実 MySQL 結合試練。
 *
 * <p>連打・応答消失後の再送で、休業行・予約取消・outbox メール・通知イベント・確認行が重ならないことを、
 * 実 HTTP 経路（MockMvc・{@code @PreAuthorize} 有効）＋実 MySQL（UNIQUE 制約・行ロック）で固定する。
 * 同時実行と TX 巻き戻しを観測するため、本試練は試験 TX で包まない（{@code @Transactional} を付けない）。
 * 所有行は {@link #所有行とadvisorを片付ける()} で明示的に消す。</p>
 *
 * <ul>
 *   <li>0334-4: 同キー同内容の2回目は 200・新行なし・同 closureId/sentCount、outbox・通知行（AFTER_COMMIT 後の実配送行）・確認行は1回分</li>
 *   <li>0334-5: 同キー別内容は 409（RESERVATION_055）・副作用0</li>
 *   <li>0334-6: 同キー同時2本で行1つ・両方同じ closureId/sentCount。後着が「初回照会で未検出 → UNIQUE 違反 → rollback →
 *       新 TX で再読込」の経路を通ったことを同期点と観測 advisor で断定する</li>
 *   <li>0334-7/12: 他テナント ADMIN が対象チーム URL へ POST → 403・副作用0、MEMBER 403、キーは team×送信者で区別</li>
 *   <li>0334-9: commit 済み・応答消失 → 同キー再送で新行なし</li>
 *   <li>0334-10: 途中失敗 → 全 rollback → 同キー再送で成功・副作用1回分</li>
 *   <li>0334-11: 予約取消あり/なし・同キー別内容の同時実行で、勝者1回分だけが残り敗者は 409・副作用0</li>
 *   <li>ヘッダ無しは従来どおり毎回作成（201）、形式不正は 400（RESERVATION_056）</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Isolated("共有Bean（outbox・通知リスナー）へ観測advisorを試練中だけ差し込むため")
@DisplayName("CMP-261104-0334 臨時休業通知送信の冪等性（実MySQL結合）")
class EmergencyClosureIdempotencyIT extends AbstractMySqlIntegrationTest {

    private static final String KEY_HEADER = "Idempotency-Key";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ReservationRepository reservations;
    @Autowired private ReservationSlotRepository slots;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EmailOutboxService emailOutboxService;
    @Autowired private com.mannschaft.app.reservation.repository.EmergencyClosureConfirmationRepository confirmationRepository;
    @Autowired private com.mannschaft.app.reservation.service.EmergencyClosureService closureService;
    @Autowired @Qualifier("event-pool") private ThreadPoolTaskExecutor eventPool;

    @PersistenceContext
    private EntityManager em;

    private final List<Long> ownedTeams = new ArrayList<>();
    private final List<Long> ownedUsers = new ArrayList<>();
    private final List<Runnable> advisorRemovals = new ArrayList<>();
    private final ExecutorService workers = Executors.newFixedThreadPool(2);

    private Long teamA;
    private Long teamB;
    private Long adminA;
    private Long deputyA;
    private Long memberA;
    private Long adminB;
    private Long patient1;
    private Long patient2;
    private Long slotA;
    private LocalDate closureDate;

    @BeforeEach
    void チームと予約者と予約を用意する() {
        closureDate = LocalDate.now().plusDays(30);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            teamA = insertTeam();
            teamB = insertTeam();
            adminA = insertUser();
            deputyA = insertUser();
            memberA = insertUser();
            adminB = insertUser();
            patient1 = insertUser();
            patient2 = insertUser();
            MembershipTestHelper.insertMembership(em, adminA, ScopeType.TEAM, teamA, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, adminA, "ADMIN", teamA, null);
            MembershipTestHelper.insertMembership(em, deputyA, ScopeType.TEAM, teamA, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, deputyA, "DEPUTY_ADMIN", teamA, null);
            MembershipTestHelper.insertMembership(em, memberA, ScopeType.TEAM, teamA, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, adminB, ScopeType.TEAM, teamB, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, adminB, "ADMIN", teamB, null);
        });
        // 予約2件で満席の枠（AVAILABLE のまま。取消で booked_count が 2→0 になる）。
        slotA = slots.saveAndFlush(ReservationSlotEntity.builder().teamId(teamA).title("CMP0334枠")
                .slotDate(closureDate).endDate(closureDate)
                .startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(11, 0))
                .capacity(2).bookedCount(2).slotStatus(SlotStatus.AVAILABLE).build()).getId();
        for (Long patient : List.of(patient1, patient2)) {
            reservations.saveAndFlush(ReservationEntity.builder().teamId(teamA).userId(patient).lineId(1L)
                    .reservationSlotId(slotA).status(ReservationStatus.CONFIRMED)
                    .bookedAt(LocalDateTime.now()).isGroupPrimary(true).build());
        }
    }

    @AfterEach
    void 所有行とadvisorを片付ける() throws Exception {
        advisorRemovals.forEach(Runnable::run);
        workers.shutdownNow();
        assertThat(workers.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        SecurityContextHolder.clearContext();
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() ->
                eventPool.getActiveCount() == 0 && eventPool.getThreadPoolExecutor().getQueue().isEmpty());
        for (Long team : ownedTeams) {
            jdbc.update("DELETE FROM emergency_closure_confirmations WHERE emergency_closure_id IN "
                    + "(SELECT id FROM emergency_closures WHERE team_id = ?)", team);
            jdbc.update("DELETE FROM emergency_closures WHERE team_id = ?", team);
            jdbc.update("DELETE FROM reservations WHERE team_id = ?", team);
            jdbc.update("DELETE FROM reservation_slots WHERE team_id = ?", team);
        }
        for (Long user : ownedUsers) {
            jdbc.update("DELETE FROM email_outbox WHERE user_id = ?", user);
            jdbc.update("DELETE FROM notifications WHERE user_id = ?", user);
            jdbc.update("DELETE FROM user_roles WHERE user_id = ?", user);
            jdbc.update("DELETE FROM memberships WHERE user_id = ?", user);
            jdbc.update("DELETE FROM users WHERE id = ?", user);
        }
        for (Long team : ownedTeams) {
            jdbc.update("DELETE FROM teams WHERE id = ?", team);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 0334-4 / 0334-9: 同キー同内容の再送
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("0334-4: 同キー同内容の2回目は200で新行なし・同closureId/sentCount、outbox・通知行・確認行は1回分")
    void 同キー同内容の再送は既存結果を返し副作用を重ねない() throws Exception {
        String key = UUID.randomUUID().toString();

        MockHttpServletResponse first = send(adminA, teamA, body(true, "体調不良"), key);
        MockHttpServletResponse second = send(adminA, teamA, body(true, "体調不良"), key);

        assertThat(first.getStatus()).isEqualTo(201);
        assertSoftly(soft -> {
            soft.assertThat(second.getStatus()).as("再送は新規作成ではないので 200").isEqualTo(200);
            soft.assertThat(closureId(second)).isEqualTo(closureId(first));
            soft.assertThat(sentCount(second)).isEqualTo(sentCount(first)).isEqualTo(2);
            soft.assertThat(closures(teamA)).isEqualTo(1);
            soft.assertThat(outbox(teamA)).as("予約者2人へのメールは1回分").isEqualTo(2);
            soft.assertThat(confirmations(teamA)).as("確認行は1回分").isEqualTo(2);
            soft.assertThat(deliveredNotifications()).as("AFTER_COMMIT 後の通知行は予約者2人ぶん（1回分）").isEqualTo(2);
            soft.assertThat(bookedCount()).as("枠の減算は1回分").isZero();
        });
    }

    @Test
    @DisplayName("0334-9: commit済みで応答が失われた後の同キー再送は新行を作らず同じ結果を返す")
    void 応答消失後の再送は新行を作らない() throws Exception {
        String key = UUID.randomUUID().toString();
        // 1回目は commit まで完了したが、応答がクライアントへ届かなかった（結果を捨てる）。
        assertThat(send(adminA, teamA, body(false, "応答消失"), key).getStatus()).isEqualTo(201);
        Long committedId = jdbc.queryForObject("SELECT id FROM emergency_closures WHERE team_id = ?", Long.class, teamA);

        MockHttpServletResponse retry = send(adminA, teamA, body(false, "応答消失"), key);

        assertSoftly(soft -> {
            soft.assertThat(retry.getStatus()).isEqualTo(200);
            soft.assertThat(closureId(retry)).isEqualTo(committedId);
            soft.assertThat(closures(teamA)).isEqualTo(1);
            soft.assertThat(outbox(teamA)).isEqualTo(2);
            soft.assertThat(deliveredNotifications()).isEqualTo(2);
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // 0334-5: 同キー別内容
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("0334-5: 同キー別内容は409(RESERVATION_055)で拒否し、副作用0（行・メール・通知・確認行・取消が増えない）")
    void 同キー別内容は409で副作用0() throws Exception {
        String key = UUID.randomUUID().toString();
        assertThat(send(adminA, teamA, body(false, "理由A"), key).getStatus()).isEqualTo(201);

        MockHttpServletResponse conflict = send(adminA, teamA, body(true, "理由B"), key);

        assertSoftly(soft -> {
            soft.assertThat(conflict.getStatus()).isEqualTo(409);
            soft.assertThat(errorCode(conflict)).isEqualTo("RESERVATION_055");
            soft.assertThat(closures(teamA)).isEqualTo(1);
            soft.assertThat(outbox(teamA)).isEqualTo(2);
            soft.assertThat(confirmations(teamA)).isEqualTo(2);
            soft.assertThat(deliveredNotifications()).isEqualTo(2);
            soft.assertThat(activeReservations()).as("別内容の取消は実行されない").isEqualTo(2);
            soft.assertThat(bookedCount()).isEqualTo(2);
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // 0334-6 / 0334-11: 同時実行
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("0334-6: 同キー同内容の同時2本で行は1つ・両方同じclosureId/sentCount、副作用は1回分")
    void 同キー同時2本で行は1つ() throws Exception {
        String key = UUID.randomUUID().toString();
        Map<String, Object> request = body(true, "同時送信");

        RaceResult race = race(request, request, key);

        assertCollisionPath(race);
        assertSoftly(soft -> {
            soft.assertThat(List.of(race.first().getStatus(), race.second().getStatus()))
                    .containsExactlyInAnyOrder(201, 200);
            soft.assertThat(closureId(race.first())).isEqualTo(closureId(race.second()));
            soft.assertThat(sentCount(race.first())).isEqualTo(sentCount(race.second())).isEqualTo(2);
            soft.assertThat(closures(teamA)).isEqualTo(1);
            soft.assertThat(outbox(teamA)).isEqualTo(2);
            soft.assertThat(confirmations(teamA)).isEqualTo(2);
            soft.assertThat(deliveredNotifications()).isEqualTo(2);
            soft.assertThat(bookedCount()).as("枠の減算は1回分").isZero();
        });
    }

    @Test
    @DisplayName("0334-11a: 予約取消あり(先行)・取消なし(後着)の同キー別内容の同時実行は、先行1回分だけ残り後着は409")
    void 取消あり先行と取消なし後着の同時実行() throws Exception {
        String key = UUID.randomUUID().toString();

        RaceResult race = race(body(true, "取消あり"), body(false, "取消なし"), key);

        assertCollisionPath(race);
        assertSoftly(soft -> {
            soft.assertThat(race.first().getStatus()).isEqualTo(201);
            soft.assertThat(race.second().getStatus()).isEqualTo(409);
            soft.assertThat(errorCode(race.second())).isEqualTo("RESERVATION_055");
            soft.assertThat(closures(teamA)).isEqualTo(1);
            soft.assertThat(outbox(teamA)).isEqualTo(2);
            soft.assertThat(confirmations(teamA)).isEqualTo(2);
            soft.assertThat(deliveredNotifications()).as("AFTER_COMMIT の通知配送は勝者の1回分").isEqualTo(2);
            soft.assertThat(activeReservations()).isZero();
            soft.assertThat(bookedCount()).isZero();
        });
    }

    @Test
    @DisplayName("0334-11b: 予約取消なし(先行)・取消あり(後着)の同キー別内容の同時実行は、後着の取消・枠減算まで全て巻き戻り409")
    void 取消なし先行と取消あり後着の同時実行() throws Exception {
        String key = UUID.randomUUID().toString();

        RaceResult race = race(body(false, "取消なし"), body(true, "取消あり"), key);

        assertCollisionPath(race);
        assertSoftly(soft -> {
            soft.assertThat(race.first().getStatus()).isEqualTo(201);
            soft.assertThat(race.second().getStatus()).isEqualTo(409);
            soft.assertThat(errorCode(race.second())).isEqualTo("RESERVATION_055");
            soft.assertThat(closures(teamA)).isEqualTo(1);
            soft.assertThat(outbox(teamA)).isEqualTo(2);
            soft.assertThat(confirmations(teamA)).isEqualTo(2);
            soft.assertThat(deliveredNotifications()).isEqualTo(2);
            soft.assertThat(activeReservations()).as("後着の取消は巻き戻る").isEqualTo(2);
            soft.assertThat(bookedCount()).as("後着の枠減算は巻き戻る").isEqualTo(2);
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // 0334-10: 途中失敗 → 全 rollback → 再送
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("0334-10: 送信途中の失敗は全rollback（休業行・取消・メール・確認行）し、同キー再送で成功・副作用1回分")
    void 途中失敗は全rollbackし同キー再送で成功する() throws Exception {
        String key = UUID.randomUUID().toString();
        // 休業行・予約取消・枠減算・outbox・通知イベント発行・確認行（1件目）まで実処理を進めた後、
        // TX 内で最後に呼ばれる確認行の保存を 2 件目で失敗させる（commit 前の途中失敗）。
        AtomicInteger saveCalls = new AtomicInteger();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        advise(confirmationRepository, "save", invocation -> {
            Object saved = invocation.proceed();
            if (saveCalls.incrementAndGet() >= 2 && failOnce.getAndSet(false)) {
                throw new IllegalStateException("CMP0334 試練: 確認行作成の途中失敗（副作用作成後・commit 前）");
            }
            return saved;
        });

        MockHttpServletResponse failed = send(adminA, teamA, body(true, "途中失敗"), key);
        assertSoftly(soft -> {
            soft.assertThat(saveCalls.get()).as("確認行まで実処理が進んでから失敗している").isGreaterThanOrEqualTo(2);
            soft.assertThat(failed.getStatus()).isEqualTo(500);
            soft.assertThat(closures(teamA)).as("休業行は巻き戻る").isZero();
            soft.assertThat(outbox(teamA)).as("outbox は巻き戻る").isZero();
            soft.assertThat(confirmations(teamA)).as("確認行は巻き戻る").isZero();
            soft.assertThat(activeReservations()).as("取消は巻き戻る").isEqualTo(2);
            soft.assertThat(bookedCount()).isEqualTo(2);
            soft.assertThat(deliveredNotifications()).isZero();
        });

        MockHttpServletResponse retry = send(adminA, teamA, body(true, "途中失敗"), key);

        assertSoftly(soft -> {
            soft.assertThat(retry.getStatus()).isEqualTo(201);
            soft.assertThat(closures(teamA)).isEqualTo(1);
            soft.assertThat(outbox(teamA)).isEqualTo(2);
            soft.assertThat(confirmations(teamA)).isEqualTo(2);
            soft.assertThat(deliveredNotifications()).isEqualTo(2);
            soft.assertThat(activeReservations()).isZero();
            soft.assertThat(bookedCount()).isZero();
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // 0334-7 / 0334-12: 認可とキーの区別
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("0334-12: 他テナントADMINが対象チームURLへ同キーでPOSTしても403・副作用0、先行結果も返らない")
    void 他テナントADMINは403で副作用0() throws Exception {
        String key = UUID.randomUUID().toString();
        assertThat(send(adminA, teamA, body(false, "先行"), key).getStatus()).isEqualTo(201);

        MockHttpServletResponse denied = send(adminB, teamA, body(false, "先行"), key);
        MockHttpServletResponse deniedFresh = send(adminB, teamA, body(true, "越境"), UUID.randomUUID().toString());

        assertSoftly(soft -> {
            soft.assertThat(denied.getStatus()).isEqualTo(403);
            soft.assertThat(content(denied)).as("先行送信の結果を漏らさない").doesNotContain("\"sentCount\"");
            soft.assertThat(deniedFresh.getStatus()).isEqualTo(403);
            soft.assertThat(closures(teamA)).isEqualTo(1);
            soft.assertThat(outbox(teamA)).isEqualTo(2);
            soft.assertThat(deliveredNotifications()).isEqualTo(2);
            soft.assertThat(activeReservations()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("0334-7: MEMBERは403・副作用0")
    void MEMBERは403() throws Exception {
        MockHttpServletResponse denied = send(memberA, teamA, body(true, "MEMBER"), UUID.randomUUID().toString());

        assertSoftly(soft -> {
            soft.assertThat(denied.getStatus()).isEqualTo(403);
            soft.assertThat(closures(teamA)).isZero();
            soft.assertThat(outbox(teamA)).isZero();
            soft.assertThat(deliveredNotifications()).isZero();
            soft.assertThat(activeReservations()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("0334-7: キーはチーム×送信者で区別する（同チームの別管理者・別チームの管理者の同キーは各々新規作成）")
    void キーはチームと送信者で区別する() throws Exception {
        String key = UUID.randomUUID().toString();

        MockHttpServletResponse byAdmin = send(adminA, teamA, body(false, "同キー"), key);
        MockHttpServletResponse byDeputy = send(deputyA, teamA, body(false, "同キー"), key);
        MockHttpServletResponse byOtherTeam = send(adminB, teamB, body(false, "同キー"), key);

        assertSoftly(soft -> {
            soft.assertThat(byAdmin.getStatus()).isEqualTo(201);
            soft.assertThat(byDeputy.getStatus()).isEqualTo(201);
            soft.assertThat(byOtherTeam.getStatus()).isEqualTo(201);
            soft.assertThat(closureId(byDeputy)).isNotEqualTo(closureId(byAdmin));
            soft.assertThat(closureId(byOtherTeam)).isNotEqualTo(closureId(byAdmin));
            soft.assertThat(sentCount(byOtherTeam)).as("他チームの結果（予約者2人）を返さない").isZero();
            soft.assertThat(closures(teamA)).isEqualTo(2);
            soft.assertThat(closures(teamB)).isEqualTo(1);
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // ヘッダ無し・形式不正
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ヘッダ無しは従来どおり毎回新規作成（201）")
    void ヘッダ無しは毎回作成() throws Exception {
        MockHttpServletResponse first = send(adminA, teamA, body(false, "従来"), null);
        MockHttpServletResponse second = send(adminA, teamA, body(false, "従来"), null);

        assertSoftly(soft -> {
            soft.assertThat(first.getStatus()).isEqualTo(201);
            soft.assertThat(second.getStatus()).isEqualTo(201);
            soft.assertThat(closureId(second)).isNotEqualTo(closureId(first));
            soft.assertThat(closures(teamA)).isEqualTo(2);
            soft.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM emergency_closures WHERE team_id = ? "
                    + "AND idempotency_key IS NULL AND request_hash IS NULL", Long.class, teamA))
                    .as("キー無しの送信は冪等列を NULL のまま保存する").isEqualTo(2);
        });
    }

    @Test
    @DisplayName("形式不正のIdempotency-Keyは400(RESERVATION_056)・副作用0")
    void 形式不正のキーは400() throws Exception {
        MockHttpServletResponse invalid = send(adminA, teamA, body(true, "不正キー"), "not-a-uuid");

        assertSoftly(soft -> {
            soft.assertThat(invalid.getStatus()).isEqualTo(400);
            soft.assertThat(errorCode(invalid)).isEqualTo("RESERVATION_056");
            soft.assertThat(content(invalid)).as("生のヘッダ値を応答に含めない").doesNotContain("not-a-uuid");
            soft.assertThat(closures(teamA)).isZero();
            soft.assertThat(activeReservations()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("大文字のUUIDキーは小文字と同じキーとして扱う")
    void 大文字小文字は同じキー() throws Exception {
        String key = UUID.randomUUID().toString();

        MockHttpServletResponse lower = send(adminA, teamA, body(false, "大小"), key);
        MockHttpServletResponse upper = send(adminA, teamA, body(false, "大小"), key.toUpperCase());

        assertSoftly(soft -> {
            soft.assertThat(lower.getStatus()).isEqualTo(201);
            soft.assertThat(upper.getStatus()).isEqualTo(200);
            soft.assertThat(closureId(upper)).isEqualTo(closureId(lower));
            soft.assertThat(closures(teamA)).isEqualTo(1);
        });
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private static final String IDEMPOTENCY_CONSTRAINT = "uq_emergency_closures_team_creator_idempotency";

    /**
     * 同時送信の結果と、後着側が通った経路の記録。
     *
     * @param secondLookups 後着側の既存行照会の結果（"empty" / "found"）を呼び出し順に並べたもの
     * @param secondSendFailure 後着側の送信本体（TX 境界の外側）で観測した例外。rollback 完了後に捕捉したもの
     */
    private record RaceResult(MockHttpServletResponse first, MockHttpServletResponse second,
                              List<String> secondLookups, Throwable secondSendFailure) {
    }

    /**
     * 先行送信を outbox 投入（休業行 INSERT・予約取消の後、commit 前）で止め、その間に後着送信を走らせる。
     *
     * <p>後着が「初回の既存行照会で未検出 → 送信本体へ進入」したことを同期点で確かめてから先行を解放する
     * （照会の結果と送信本体への進入は、送信本体 Bean の TX 境界の外側に差し込んだ観測 advisor で記録する）。
     * これで、後着が先行の commit 後に逐次の再送として既存行を見つける経路では緑にならず、
     * 「UNIQUE 違反 → rollback → 新 TX で再読込」の経路を通ったことを {@link #assertCollisionPath} で断定できる。</p>
     */
    private RaceResult race(Map<String, Object> firstBody, Map<String, Object> secondBody, String key)
            throws Exception {
        AtomicReference<Thread> holder = new AtomicReference<>();
        AtomicReference<Thread> secondThread = new AtomicReference<>();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondEnteredSend = new CountDownLatch(1);
        List<String> secondLookups = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicReference<Throwable> secondSendFailure = new AtomicReference<>();
        advise(emailOutboxService, "enqueue", invocation -> {
            if (holder.compareAndSet(null, Thread.currentThread())) {
                holding.countDown();
                assertThat(release.await(60, TimeUnit.SECONDS)).as("試練側の解放待ち").isTrue();
            }
            return invocation.proceed();
        });
        advise(closureService, "findIdempotentClosure", invocation -> {
            Object result = invocation.proceed();
            if (Thread.currentThread() == secondThread.get()) {
                secondLookups.add(((java.util.Optional<?>) result).isPresent() ? "found" : "empty");
            }
            return result;
        });
        advise(closureService, "sendClosure", invocation -> {
            if (Thread.currentThread() != secondThread.get()) {
                return invocation.proceed();
            }
            secondEnteredSend.countDown();
            try {
                return invocation.proceed();
            } catch (Throwable failure) {
                // この advisor は TransactionInterceptor の外側にあるため、ここで見える例外は rollback 完了後のもの。
                secondSendFailure.set(failure);
                throw failure;
            }
        });
        Future<MockHttpServletResponse> first = workers.submit(() -> send(adminA, teamA, firstBody, key));
        assertThat(holding.await(60, TimeUnit.SECONDS)).as("先行送信が commit 前で止まった").isTrue();
        Future<MockHttpServletResponse> second = workers.submit(() -> {
            secondThread.set(Thread.currentThread());
            return send(adminA, teamA, secondBody, key);
        });
        // 同期点: 後着が初回照会で既存行を見つけられず（先行は未 commit）、送信本体へ進入した。
        assertThat(secondEnteredSend.await(60, TimeUnit.SECONDS)).as("後着が送信本体へ進入した").isTrue();
        assertThat(secondLookups).as("後着の初回照会は未検出（先行は未 commit）").containsExactly("empty");
        // 後着が先行の commit 待ち（UNIQUE 行または予約行のロック待ち）で止まっていることを確かめてから解放する。
        Awaitility.await().during(Duration.ofMillis(700)).atMost(Duration.ofSeconds(10))
                .until(() -> !second.isDone());
        release.countDown();
        return new RaceResult(first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS),
                List.copyOf(secondLookups), secondSendFailure.get());
    }

    /**
     * 後着が「初回照会で未検出 → 送信本体で冪等キーの UNIQUE 違反 → rollback → 新 TX で再読込（検出）」の
     * 経路を実際に通ったことを断定する。
     */
    private void assertCollisionPath(RaceResult race) {
        assertThat(race.secondLookups()).as("後着の照会は 未検出 → (UNIQUE 違反後の新 TX で) 検出 の2回")
                .containsExactly("empty", "found");
        assertThat(race.secondSendFailure()).as("後着の送信本体は失敗して rollback された").isNotNull();
        boolean uniqueViolation = false;
        for (Throwable cause = race.secondSendFailure(); cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(IDEMPOTENCY_CONSTRAINT)) {
                uniqueViolation = true;
                break;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        assertThat(uniqueViolation).as("失敗の原因は冪等キーの一意制約違反: " + race.secondSendFailure()).isTrue();
    }

    /** AFTER_COMMIT 後に非同期配送で実際にできた臨時休業の通知行の件数（予約者2人ぶん）。 */
    private long deliveredNotifications() {
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() ->
                eventPool.getActiveCount() == 0 && eventPool.getThreadPoolExecutor().getQueue().isEmpty());
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE notification_type = 'EMERGENCY_CLOSURE' "
                + "AND source_type = 'EMERGENCY_CLOSURE' AND user_id IN (?, ?)", Long.class, patient1, patient2);
    }

    private MockHttpServletResponse send(Long actor, Long team, Map<String, Object> body, String key)
            throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor.toString(), null, List.of()));
        try {
            MockHttpServletRequestBuilder request = post("/api/v1/teams/{teamId}/emergency-closures", team)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body));
            if (key != null) {
                request.header(KEY_HEADER, key);
            }
            MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();
            response.setCharacterEncoding("UTF-8");
            return response;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Map<String, Object> body(boolean cancelReservations, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("startDate", closureDate.toString());
        body.put("endDate", closureDate.toString());
        body.put("reason", reason);
        body.put("subject", "臨時休業のお知らせ");
        body.put("messageBody", "本日は休業します（" + reason + "）");
        body.put("cancelReservations", cancelReservations);
        return body;
    }

    private Long closureId(MockHttpServletResponse response) {
        JsonNode id = json(response).path("data").path("id");
        return id.isMissingNode() || id.isNull() ? null : id.asLong();
    }

    private Integer sentCount(MockHttpServletResponse response) {
        JsonNode count = json(response).path("data").path("sentCount");
        return count.isMissingNode() || count.isNull() ? null : count.asInt();
    }

    private String errorCode(MockHttpServletResponse response) {
        return json(response).path("error").path("code").asText(null);
    }

    private JsonNode json(MockHttpServletResponse response) {
        String content = content(response);
        try {
            return content.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(content);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String content(MockHttpServletResponse response) {
        try {
            return response.getContentAsString();
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private long closures(Long team) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM emergency_closures WHERE team_id = ?", Long.class, team);
    }

    private long outbox(Long team) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM email_outbox o WHERE EXISTS (SELECT 1 FROM emergency_closures c "
                + "WHERE c.team_id = ? AND o.source_event_id LIKE CONCAT('emergency-closure:', c.id, ':%'))",
                Long.class, team);
    }

    private long confirmations(Long team) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM emergency_closure_confirmations WHERE emergency_closure_id IN "
                + "(SELECT id FROM emergency_closures WHERE team_id = ?)", Long.class, team);
    }

    private long activeReservations() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM reservations WHERE reservation_slot_id = ? "
                + "AND status IN ('PENDING', 'CONFIRMED')", Long.class, slotA);
    }

    private int bookedCount() {
        return jdbc.queryForObject("SELECT booked_count FROM reservation_slots WHERE id = ?", Integer.class, slotA);
    }

    private void advise(Object bean, String methodName, MethodInterceptor interceptor) {
        Advisor advisor = new DefaultPointcutAdvisor(new StaticMethodMatcherPointcut() {
            @Override
            public boolean matches(Method method, Class<?> targetClass) {
                return method.getName().equals(methodName);
            }
        }, interceptor);
        ((Advised) bean).addAdvisor(0, advisor);
        advisorRemovals.add(() -> ((Advised) bean).removeAdvisor(advisor));
    }

    private Long insertUser() {
        String email = "cmp0334-" + UUID.randomUUID() + "@example.com";
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'CMP0334', 'テスト', 'CMP0334 テスト', 'ACTIVE', "
                                + "1, 1, 1, 'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        Long id = ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
        ownedUsers.add(id);
        return id;
    }

    private Long insertTeam() {
        String name = "CMP0334-" + UUID.randomUUID();
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('cmp0334-', LEFT(REPLACE(UUID(),'-',''),12)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        Long id = ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
        ownedTeams.add(id);
        return id;
    }
}
