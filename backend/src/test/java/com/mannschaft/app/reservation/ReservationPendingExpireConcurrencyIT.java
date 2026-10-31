package com.mannschaft.app.reservation;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationPendingExpireScanStateEntity;
import com.mannschaft.app.reservation.entity.ReservationPolicyEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationPendingExpireScanStateRepository;
import com.mannschaft.app.reservation.repository.ReservationPolicyRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.service.ReservationPendingExpireBatchService;
import com.mannschaft.app.reservation.service.ReservationPendingExpireService;
import com.mannschaft.app.reservation.service.ReservationService;
import com.mannschaft.app.reservation.service.ReservationSlotService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * CMP-260822-1730 AC-1730-6 / AC-1730-4 の実 MySQL 試練。
 *
 * <p>AC-1730-6: 管理者承認と仮押さえ失効バッチが同じ PENDING を並行に遷移させても、最終状態は
 * 「CONFIRMED かつ枠不変」か「CANCELLED かつ枠減算」のどちらかに必ず一致する（過剰予約ゼロ）。
 * 承認が行ロックなしで旧 PENDING を読むと、バッチ commit 後に CONFIRMED で上書きし
 * 「確定なのに booked_count 減算済み」を作る（初期 RED）。</p>
 *
 * <p>AC-1730-4: 実バッチの commit 後配信投入が失敗しても、取消・枠減算・DB 通知・進捗は保持され、
 * 再実行で二重失効・二重通知しない。外部配信部だけを AOP で失敗させ、業務 Bean/DB はモックしない。</p>
 */
@DisplayName("CMP1730 承認と失効バッチの直列化・配信失敗の実MySQL試練")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Isolated("失効サービスClockと共有Beanへの観測advisorを所有試練中だけ差し替える")
class ReservationPendingExpireConcurrencyIT extends AbstractMySqlIntegrationTest {

    private static final Instant NOW = Instant.parse("2000-08-10T03:00:00Z");
    private static final AtomicLong USERS = new AtomicLong(7_400_000_000L);
    private static final String EXPIRED_TYPE = "RESERVATION_PENDING_EXPIRED";

    @Autowired private ReservationPendingExpireBatchService batch;
    @Autowired private ReservationPendingExpireService expireService;
    @Autowired private ReservationService reservationService;
    @Autowired private ReservationSlotService slotService;
    @Autowired private NotificationDispatchService notificationDispatch;
    @Autowired private ReservationRepository reservations;
    @Autowired private ReservationSlotRepository slots;
    @Autowired private ReservationPolicyRepository policies;
    @Autowired private ReservationPendingExpireScanStateRepository states;
    @Autowired private TeamRepository teams;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired @Qualifier("event-pool") private ThreadPoolTaskExecutor eventPool;

    private final List<Long> ownedTeams = new ArrayList<>();
    private final List<Long> ownedUsers = new ArrayList<>();
    private final List<Runnable> advisorRemovals = new ArrayList<>();
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private Object expireTarget;
    private Clock originalClock;
    private UUID stateId;

    @BeforeEach
    void 固定Clockと所有進捗行を用意する() {
        expireTarget = AopTestUtils.getUltimateTargetObject(expireService);
        originalClock = (Clock) ReflectionTestUtils.getField(expireTarget, "clock");
        assertThat(originalClock).isNotNull();
        ReflectionTestUtils.setField(expireTarget, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(expireService.findExpirableUnits()).as("前提: 他試練の失効候補が残っていない").isEmpty();
        assertThat(states.count()).as("他runnerの進捗を上書きしない").isZero();
        stateId = states.saveAndFlush(ReservationPendingExpireTestFixture.minimumValidState(NOW)).getId();
    }

    @AfterEach
    void 所有行とadvisorとClockを片付ける() throws Exception {
        advisorRemovals.forEach(Runnable::run);
        workers.shutdownNow();
        assertThat(workers.awaitTermination(30, TimeUnit.SECONDS)).as("所有workerが停止してから片付ける").isTrue();
        if (originalClock != null) {
            ReflectionTestUtils.setField(expireTarget, "clock", originalClock);
        }
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() ->
                eventPool.getActiveCount() == 0 && eventPool.getThreadPoolExecutor().getQueue().isEmpty());
        for (Long user : ownedUsers) {
            jdbc.update("DELETE FROM notifications WHERE user_id = ?", user);
        }
        if (stateId != null) {
            jdbc.update("DELETE FROM reservation_pending_expire_scan_state WHERE id = UNHEX(REPLACE(?, '-', ''))",
                    stateId.toString());
        }
        for (Long team : ownedTeams) {
            jdbc.update("DELETE FROM reservation_reminders WHERE reservation_id IN "
                    + "(SELECT id FROM reservations WHERE team_id = ?)", team);
            jdbc.update("DELETE FROM reservations WHERE team_id = ?", team);
            jdbc.update("DELETE FROM reservation_slots WHERE team_id = ?", team);
            jdbc.update("DELETE FROM reservation_policies WHERE team_id = ?", team);
            jdbc.update("DELETE FROM teams WHERE id = ?", team);
        }
    }

    @Test
    @DisplayName("AC1730-6a: バッチが行ロック中に承認が来ても、承認は状態不正で負けCANCELLED・枠減算・失効通知1件")
    void バッチ先行時は承認が負けて失効が保たれる() throws Exception {
        Long team = team();
        ReservationSlotEntity slot = slot(team);
        ReservationEntity pending = pending(team, slot);
        Thread[] batchThread = new Thread[1];
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // 失効unitが予約行ロックを保持し、取消・枠減算を終えた commit 前の地点で止める。
        advise(slotService, "decrementAndReopen", invocation -> {
            Object result = invocation.proceed();
            if (Thread.currentThread() == batchThread[0]) {
                holding.countDown();
                assertThat(release.await(60, TimeUnit.SECONDS)).as("試練側の解放待ち").isTrue();
            }
            return result;
        });

        Future<Integer> batchRun = workers.submit(() -> {
            batchThread[0] = Thread.currentThread();
            return batch.expirePendingReservations();
        });
        assertThat(holding.await(60, TimeUnit.SECONDS)).as("バッチが失効unit内で行ロックを保持した").isTrue();
        Future<?> confirm = workers.submit(() -> reservationService.confirmReservation(team, pending.getId()));
        Awaitility.await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5))
                .until(() -> !confirm.isDone());
        release.countDown();

        assertThat(batchRun.get(60, TimeUnit.SECONDS)).isEqualTo(1);
        assertThatThrownBy(() -> confirm.get(60, TimeUnit.SECONDS))
                .hasCauseInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e.getCause()).getErrorCode())
                        .isEqualTo(ReservationErrorCode.INVALID_RESERVATION_STATUS));
        assertSoftly(soft -> {
            soft.assertThat(reload(pending).getStatus()).isEqualTo(ReservationStatus.CANCELLED);
            soft.assertThat(reload(slot).getBookedCount()).as("失効の枠減算だけが残る").isZero();
            soft.assertThat(expiredNotifications(pending.getUserId())).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("AC1730-6b: 承認が行ロック中にバッチが来ても、バッチは最新CONFIRMEDを読んで失効しない・枠不変・通知なし")
    void 承認先行時はバッチが失効しない() throws Exception {
        Long team = team();
        ReservationSlotEntity slot = slot(team);
        ReservationEntity pending = pending(team, slot);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<?> confirm = workers.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            reservationService.confirmReservation(team, pending.getId());
            holding.countDown();
            try {
                assertThat(release.await(60, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }));
        assertThat(holding.await(60, TimeUnit.SECONDS)).as("承認が行ロックを保持した").isTrue();
        Future<Integer> batchRun = workers.submit(() -> batch.expirePendingReservations());
        Awaitility.await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5))
                .until(() -> !batchRun.isDone());
        release.countDown();

        confirm.get(60, TimeUnit.SECONDS);
        assertThat(batchRun.get(60, TimeUnit.SECONDS)).isZero();
        assertSoftly(soft -> {
            soft.assertThat(reload(pending).getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
            soft.assertThat(reload(slot).getBookedCount()).as("承認済みの枠は減算しない").isEqualTo(1);
            soft.assertThat(expiredNotifications(pending.getUserId())).isZero();
        });
    }

    @Test
    @DisplayName("AC1730-6c: 承認と失効を同時に開始しても最終状態は(CONFIRMED,枠1)か(CANCELLED,枠0)のどちらかだけ")
    void 同時開始でも不整合状態を作らない() throws Exception {
        Long team = team();
        for (int i = 0; i < 5; i++) {
            ReservationSlotEntity slot = slot(team);
            ReservationEntity pending = pending(team, slot);
            CyclicBarrier start = new CyclicBarrier(2);
            Future<Integer> batchRun = workers.submit(() -> {
                start.await(30, TimeUnit.SECONDS);
                return batch.expirePendingReservations();
            });
            Future<Boolean> confirm = workers.submit(() -> {
                start.await(30, TimeUnit.SECONDS);
                try {
                    reservationService.confirmReservation(team, pending.getId());
                    return true;
                } catch (BusinessException e) {
                    assertThat(e.getErrorCode()).isEqualTo(ReservationErrorCode.INVALID_RESERVATION_STATUS);
                    return false;
                }
            });
            int expired = batchRun.get(60, TimeUnit.SECONDS);
            boolean confirmed = confirm.get(60, TimeUnit.SECONDS);
            ReservationStatus status = reload(pending).getStatus();
            int booked = reload(slot).getBookedCount();
            String round = "round " + i;
            if (status == ReservationStatus.CONFIRMED) {
                assertThat(booked).as(round + " CONFIRMEDなら枠不変").isEqualTo(1);
                assertThat(confirmed).as(round).isTrue();
                assertThat(expired).as(round).isZero();
                assertThat(expiredNotifications(pending.getUserId())).as(round).isZero();
            } else {
                assertThat(status).as(round).isEqualTo(ReservationStatus.CANCELLED);
                assertThat(booked).as(round + " CANCELLEDなら枠減算").isZero();
                assertThat(confirmed).as(round + " 承認は負ける").isFalse();
                assertThat(expiredNotifications(pending.getUserId())).as(round).isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("AC1730-4b: 実バッチのcommit後配信投入が失敗しても取消・枠減算・DB通知・進捗は残り、再実行で二重処理しない")
    void 配信投入失敗でも失効は保持され再実行で二重処理しない() {
        Long team = team();
        ReservationSlotEntity slot = slot(team);
        ReservationEntity pending = pending(team, slot);
        Long owner = pending.getUserId();
        int[] dispatchFailures = {0};
        advise(notificationDispatch, "dispatch", invocation -> {
            Object notification = invocation.getArguments().length > 0 ? invocation.getArguments()[0] : null;
            if (notification instanceof com.mannschaft.app.notification.entity.NotificationEntity n
                    && owner.equals(n.getUserId()) && EXPIRED_TYPE.equals(n.getNotificationType())) {
                dispatchFailures[0]++;
                throw new IllegalStateException("CMP1730 owned external dispatch failure");
            }
            return invocation.proceed();
        });

        assertThat(batch.expirePendingReservations()).isEqualTo(1);

        assertThat(dispatchFailures[0]).as("commit後の配信投入まで到達して失敗した").isEqualTo(1);
        ReservationPendingExpireScanStateEntity state = states.findById(stateId).orElseThrow();
        assertSoftly(soft -> {
            soft.assertThat(reload(pending).getStatus()).isEqualTo(ReservationStatus.CANCELLED);
            soft.assertThat(reload(slot).getBookedCount()).isZero();
            soft.assertThat(expiredNotifications(owner)).as("DB通知はcommit済みで残る").isEqualTo(1);
            soft.assertThat(state.getRetryPrimaryIds()).as("成功unitはretryへ残さない").isEmpty();
            soft.assertThat(state.getRunEpoch()).isEqualTo(1);
        });

        assertThat(batch.expirePendingReservations()).as("再実行で二重失効しない").isZero();
        assertSoftly(soft -> {
            soft.assertThat(dispatchFailures[0]).as("再実行で再配信しない").isEqualTo(1);
            soft.assertThat(reload(slot).getBookedCount()).as("枠を二重減算しない").isZero();
            soft.assertThat(expiredNotifications(owner)).as("通知を二重作成しない").isEqualTo(1);
            soft.assertThat(states.findById(stateId).orElseThrow().getRetryPrimaryIds()).isEmpty();
        });
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

    private Long team() {
        String suffix = UUID.randomUUID().toString().substring(0, 12);
        TeamEntity team = teams.saveAndFlush(TeamEntity.builder().name("CMP1730R-" + suffix)
                .slug("cmp1730r-" + suffix).timezone("Asia/Tokyo")
                .visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(false).build());
        ownedTeams.add(team.getId());
        policies.saveAndFlush(ReservationPolicyEntity.builder().teamId(team.getId()).pendingExpireHours(24).build());
        return team.getId();
    }

    private ReservationSlotEntity slot(Long team) {
        // AVAILABLEのまま減算できる未来枠。待機通知の非同期連鎖を発生させない。
        return slots.saveAndFlush(ReservationSlotEntity.builder().teamId(team).title("CMP1730競合枠")
                .slotDate(LocalDate.of(2000, 8, 12)).endDate(LocalDate.of(2000, 8, 12))
                .startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(10, 30))
                .capacity(2).bookedCount(1).slotStatus(SlotStatus.AVAILABLE).build());
    }

    private ReservationEntity pending(Long team, ReservationSlotEntity slot) {
        Long user = USERS.incrementAndGet();
        ownedUsers.add(user);
        // 24h ポリシーで期限切れ（25時間前）の単枠 PENDING。
        return reservations.saveAndFlush(ReservationEntity.builder().teamId(team).userId(user).lineId(1L)
                .reservationSlotId(slot.getId()).status(ReservationStatus.PENDING)
                .bookedAt(LocalDateTime.ofInstant(NOW.minusSeconds(25 * 3600L), UserZoneLocalDateTimeParser.SERVER_ZONE))
                .isGroupPrimary(true).build());
    }

    private ReservationEntity reload(ReservationEntity row) {
        return reservations.findById(row.getId()).orElseThrow();
    }

    private ReservationSlotEntity reload(ReservationSlotEntity slot) {
        return slots.findById(slot.getId()).orElseThrow();
    }

    private long expiredNotifications(Long user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ? AND notification_type = ?",
                Long.class, user, EXPIRED_TYPE);
    }
}
