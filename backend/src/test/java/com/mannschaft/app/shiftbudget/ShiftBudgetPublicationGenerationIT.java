package com.mannschaft.app.shiftbudget;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.event.ShiftPublishedEvent;
import com.mannschaft.app.shift.event.ShiftScheduleClosedEvent;
import com.mannschaft.app.shift.repository.ShiftHourlyRateRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.shift.service.ShiftScheduleService;
import com.mannschaft.app.shiftbudget.listener.ShiftBudgetConsumptionCancelListener;
import com.mannschaft.app.shiftbudget.listener.ShiftBudgetConsumptionRecordListener;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.support.test.TeamOrgFixtureHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

/**
 * CMP-261021-0718: 実公開イベントの逆順配送と、同じ割当の取消履歴保全を検査する。
 *
 * <p>テスト全体をTransactionalで包まず、fixtureと公開状態遷移を実際にcommitする。
 * 実listenerのspyは配送の開始・完了を観測し、最初のcloseだけDB操作より前で待たせる。
 * 戻り値・SQL結果・業務処理は置換せず、callRealMethodで実Beanと実MySQLを通す。</p>
 * <p>AからBへ割当を変更するため、旧CANCELLEDの一意制約衝突が新消化を偶然保護する
 * 偽の緑を避ける。Bの記録を確認してからcloseを解放し、実配送完了後に状態と実額を検査する。
 * コンパイル・起動・fixture・同期点の失敗は業務assertionのredとは区別する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@TestPropertySource(properties = "feature.shift-budget.enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("0718 公開世代・取消履歴の受け入れ試練（実DB）")
class ShiftBudgetPublicationGenerationIT extends AbstractMySqlIntegrationTest {
    private static final BigDecimal FIRST_RATE = new BigDecimal("1250.00");
    private static final BigDecimal SECOND_RATE = new BigDecimal("2000.00");
    private static final BigDecimal FIRST_AMOUNT = new BigDecimal("10000.00");
    private static final BigDecimal SECOND_AMOUNT = new BigDecimal("16000.00");
    private static final Duration LIMIT = Duration.ofSeconds(30);

    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private ShiftScheduleRepository schedules;
    @Autowired private ShiftSlotRepository slots;
    @Autowired private ShiftHourlyRateRepository rates;
    @Autowired private ShiftBudgetAllocationRepository allocations;
    @Autowired private ShiftScheduleService scheduleService;
    @Autowired @Qualifier("event-pool") private ThreadPoolTaskExecutor eventExecutor;
    @PersistenceContext private EntityManager em;

    @MockitoSpyBean private ShiftBudgetConsumptionRecordListener recordListener;
    @MockitoSpyBean private ShiftBudgetConsumptionCancelListener cancelListener;

    private final BlockingQueue<ShiftPublishedEvent> completedPublications = new LinkedBlockingQueue<>();
    private final BlockingQueue<ShiftScheduleClosedEvent> enteredCloses = new LinkedBlockingQueue<>();
    private final BlockingQueue<ShiftScheduleClosedEvent> completedCloses = new LinkedBlockingQueue<>();
    private final AtomicBoolean holdNextClose = new AtomicBoolean();
    private final CountDownLatch releaseClose = new CountDownLatch(1);
    private final AtomicReference<Throwable> deliveryFailure = new AtomicReference<>();
    private final AtomicReference<String> closeThread = new AtomicReference<>();

    private Long organizationId;
    private Long teamId;
    private Long firstUserId;
    private Long secondUserId;
    private Long scheduleId;
    private Long slotId;
    private Long allocationId;

    @BeforeEach
    void setUp() {
        // 待機中のcloseとは別の実workerで再公開が進む構成を前提として検査する。
        assertThat(eventExecutor.getCorePoolSize()).as("実event-poolの並行worker数").isGreaterThanOrEqualTo(2);
        String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        transactionTemplate.executeWithoutResult(tx -> {
            organizationId = TeamOrgFixtureHelper.insertOrganization(em, "0718組織", "o-" + nonce);
            teamId = TeamOrgFixtureHelper.insertTeam(em, "0718チーム", "t-" + nonce);
            TeamOrgFixtureHelper.insertTeamOrgMembership(em, teamId, organizationId, "ACTIVE",
                    LocalDateTime.of(2026, 6, 1, 0, 0));
            firstUserId = users.save(ShiftBudgetPublicationGenerationTestFixture.user(nonce, "A")).getId();
            secondUserId = users.save(ShiftBudgetPublicationGenerationTestFixture.user(nonce, "B")).getId();
            MembershipTestHelper.insertMembership(em, firstUserId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, secondUserId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, firstUserId, "ADMIN", teamId, null);
            scheduleId = schedules.save(ShiftBudgetPublicationGenerationTestFixture.schedule(teamId, firstUserId)).getId();
            slotId = slots.save(ShiftBudgetPublicationGenerationTestFixture.slot(scheduleId, firstUserId)).getId();
            allocationId = allocations.save(ShiftBudgetPublicationGenerationTestFixture
                    .allocation(organizationId, teamId, firstUserId)).getId();
            rates.save(ShiftBudgetPublicationGenerationTestFixture.rate(teamId, firstUserId, FIRST_RATE));
            rates.save(ShiftBudgetPublicationGenerationTestFixture.rate(teamId, secondUserId, SECOND_RATE));
        });
        observeRealDeliveries();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                firstUserId.toString(), null, List.of()));
    }

    /** 実listenerの入口だけで順序を制御し、DBロックを保持したまま再公開を待たない。 */
    private void observeRealDeliveries() {
        ShiftBudgetConsumptionRecordListener recordTarget = AopTestUtils.getUltimateTargetObject(recordListener);
        ShiftBudgetConsumptionCancelListener cancelTarget = AopTestUtils.getUltimateTargetObject(cancelListener);
        assertThat(mockingDetails(recordTarget).isSpy()).as("公開listenerのAOP内側の実spy").isTrue();
        assertThat(mockingDetails(cancelTarget).isSpy()).as("取消listenerのAOP内側の実spy").isTrue();
        doAnswer(invocation -> {
            ShiftPublishedEvent event = invocation.getArgument(0);
            if (!scheduleId.equals(event.getScheduleId())) return invocation.callRealMethod();
            try {
                return invocation.callRealMethod();
            } catch (Throwable failure) {
                deliveryFailure.compareAndSet(null, failure);
                throw failure;
            } finally {
                completedPublications.add(event);
            }
        }).when(recordTarget).onShiftPublished(any(ShiftPublishedEvent.class));
        doAnswer(invocation -> {
            ShiftScheduleClosedEvent event = invocation.getArgument(0);
            if (!scheduleId.equals(event.getScheduleId())) return invocation.callRealMethod();
            try {
                closeThread.set(Thread.currentThread().getName());
                enteredCloses.add(event);
                if (holdNextClose.compareAndSet(true, false)) {
                    assertThat(releaseClose.await(LIMIT.toSeconds(), TimeUnit.SECONDS))
                            .as("DB操作前で保留した旧closeの有限解放待ち").isTrue();
                }
                return invocation.callRealMethod();
            } catch (Throwable failure) {
                deliveryFailure.compareAndSet(null, failure);
                throw failure;
            } finally {
                completedCloses.add(event);
            }
        }).when(cancelTarget).onShiftScheduleClosed(any(ShiftScheduleClosedEvent.class));
    }

    @AfterEach
    void cleanup() {
        releaseClose.countDown();
        awaitEventsFinished();
        SecurityContextHolder.clearContext();
        // 自分で採番したスケジュール・組織・チーム・利用者に属する行だけを撤去する。
        if (scheduleId == null) return;
        jdbc.update("DELETE FROM notification_fanout_job_messages WHERE job_id IN "
                + "(SELECT id FROM notification_fanout_jobs WHERE scope_type = 'TEAM' AND scope_ref = ?)", teamId.toString());
        jdbc.update("DELETE FROM notification_fanout_jobs WHERE scope_type = 'TEAM' AND scope_ref = ?", teamId.toString());
        jdbc.update("DELETE FROM todos WHERE linked_schedule_id = ?", scheduleId);
        jdbc.update("DELETE FROM audit_logs WHERE team_id = ? OR organization_id = ?", teamId, organizationId);
        jdbc.update("DELETE FROM shift_budget_failed_events WHERE organization_id = ?", organizationId);
        jdbc.update("DELETE FROM shift_budget_consumptions WHERE shift_id = ?", scheduleId);
        jdbc.update("DELETE FROM shift_budget_allocations WHERE id = ?", allocationId);
        jdbc.update("DELETE FROM shift_hourly_rates WHERE team_id = ?", teamId);
        jdbc.update("DELETE FROM shift_slots WHERE schedule_id = ?", scheduleId);
        jdbc.update("DELETE FROM shift_schedules WHERE id = ?", scheduleId);
        jdbc.update("DELETE FROM memberships WHERE scope_type = 'TEAM' AND scope_id = ?", teamId);
        jdbc.update("DELETE FROM user_roles WHERE team_id = ?", teamId);
        jdbc.update("DELETE FROM team_org_memberships WHERE team_id = ?", teamId);
        jdbc.update("DELETE FROM teams WHERE id = ?", teamId);
        jdbc.update("DELETE FROM organizations WHERE id = ?", organizationId);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", firstUserId, secondUserId);
    }

    @Test
    @Timeout(120)
    @DisplayName("AC02/05 旧closeを再公開後に配送しても、新しい担当BのPLANNEDと16000円を維持する")
    void 担当変更して再公開した後の旧取消は新消化を取り消さない() throws Exception {
        publishAndAwait();
        assertThat(consumedAmount()).isEqualByComparingTo(FIRST_AMOUNT);
        assertThat(plannedCount(firstUserId)).isEqualTo(1);

        holdNextClose.set(true);
        scheduleService.transitionStatus(scheduleId, "ADJUSTING", firstUserId);
        ShiftScheduleClosedEvent held = required(enteredCloses, "旧closeが実listenerへ到達");
        assertThat(closeThread.get()).startsWith("event-");
        assertThat(completedCloses).isEmpty();

        transactionTemplate.executeWithoutResult(tx -> slots.findById(slotId).orElseThrow()
                .updateAssignedUserIds("[" + secondUserId + "]"));
        publishAndAwait();
        assertThat(secondUserId).isNotEqualTo(firstUserId);
        assertThat(plannedCount(secondUserId)).as("旧slot/userとは異なる新Bの実PLANNED").isEqualTo(1);
        assertThat(plannedAmount(secondUserId)).as("旧close解放より先に新Bの実額が記録済み").isEqualByComparingTo(SECOND_AMOUNT);
        assertThat(cancelledCount(secondUserId)).as("既存CANCELLEDとの衝突による偶然の保護を除く").isZero();

        releaseClose.countDown();
        assertThat(required(completedCloses, "保留した旧closeの実処理完了")).isSameAs(held);
        assertThat(deliveryFailure.get()).as("配送同期点の失敗は業務redと区別する").isNull();
        awaitEventsFinished();

        assertAll(
                () -> assertThat(plannedCount(secondUserId)).as("旧close配送後も新BがPLANNED").isEqualTo(1),
                () -> assertThat(consumedAmount()).as("旧close配送後も新Bの16000円を維持").isEqualByComparingTo(SECOND_AMOUNT),
                () -> assertThat(cancelledCount(firstUserId)).as("旧Aの消化は収束済み").isEqualTo(1));
    }

    @Test
    @Timeout(120)
    @DisplayName("AC06 同じslot/userの公開と取消を2周しても、取消履歴2件・PLANNED0件・実額0円になる")
    void 同じ担当の公開取消を繰り返しても取消履歴を全件保全する() throws Exception {
        publishAndAwait();
        closeAndAwait();
        assertThat(plannedCount(firstUserId)).as("1周目の取消が実際に完了").isZero();
        assertThat(cancelledCount(firstUserId)).isEqualTo(1);
        assertThat(consumedAmount()).isEqualByComparingTo(BigDecimal.ZERO);

        publishAndAwait();
        assertThat(plannedCount(firstUserId)).as("同じslot/userの2周目が記録済み").isEqualTo(1);
        assertThat(consumedAmount()).isEqualByComparingTo(FIRST_AMOUNT);
        closeAndAwait();

        assertAll(
                () -> assertThat(plannedCount(firstUserId)).as("2周目の取消後にPLANNEDを残さない").isZero(),
                () -> assertThat(consumedAmount()).as("2周目も実消化額を0円へ戻す").isEqualByComparingTo(BigDecimal.ZERO),
                () -> assertThat(cancelledCount(firstUserId)).as("同一slot/userの取消履歴を2件保持").isEqualTo(2));
    }

    private void publishAndAwait() throws InterruptedException {
        scheduleService.transitionStatus(scheduleId, "PUBLISHED", firstUserId);
        assertThat(required(completedPublications, "公開イベントの実記録listener完了").getScheduleId()).isEqualTo(scheduleId);
        assertThat(deliveryFailure.get()).isNull();
    }

    private void closeAndAwait() throws InterruptedException {
        scheduleService.transitionStatus(scheduleId, "ADJUSTING", firstUserId);
        assertThat(required(completedCloses, "公開取消イベントの実listener完了").getScheduleId()).isEqualTo(scheduleId);
        assertThat(deliveryFailure.get()).isNull();
        awaitEventsFinished();
    }

    private <T> T required(BlockingQueue<T> queue, String point) throws InterruptedException {
        T event = queue.poll(LIMIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(event).as(point).isNotNull();
        return event;
    }

    private int plannedCount(Long userId) {
        return count(userId, "PLANNED");
    }

    private int cancelledCount(Long userId) {
        return count(userId, "CANCELLED");
    }

    private int count(Long userId, String status) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM shift_budget_consumptions "
                + "WHERE shift_id = ? AND slot_id = ? AND user_id = ? AND status = ? AND deleted_at IS NULL",
                Integer.class, scheduleId, slotId, userId, status);
    }

    private BigDecimal plannedAmount(Long userId) {
        return jdbc.queryForObject("SELECT amount FROM shift_budget_consumptions "
                + "WHERE shift_id = ? AND slot_id = ? AND user_id = ? AND status = 'PLANNED' AND deleted_at IS NULL",
                BigDecimal.class, scheduleId, slotId, userId);
    }

    private BigDecimal consumedAmount() {
        return jdbc.queryForObject("SELECT consumed_amount FROM shift_budget_allocations WHERE id = ?",
                BigDecimal.class, allocationId);
    }

    private void awaitEventsFinished() {
        await().atMost(LIMIT).until(() -> eventExecutor.getActiveCount() == 0
                && eventExecutor.getThreadPoolExecutor().getQueue().isEmpty());
    }
}
