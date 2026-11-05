package com.mannschaft.app.shiftbudget;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.event.ShiftPublishedEvent;
import com.mannschaft.app.shift.event.ShiftArchivedEvent;
import com.mannschaft.app.shift.event.ShiftScheduleClosedEvent;
import com.mannschaft.app.shift.repository.ShiftHourlyRateRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.shift.service.ShiftScheduleService;
import com.mannschaft.app.shift.service.ShiftSlotService;
import com.mannschaft.app.shiftbudget.batch.ShiftBudgetConsumptionReconcileBatchService;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetConsumptionService;
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
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
    @Autowired private com.mannschaft.app.common.DomainEventPublisher eventPublisher;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private ShiftScheduleRepository schedules;
    @Autowired private ShiftSlotRepository slots;
    @Autowired private ShiftHourlyRateRepository rates;
    @Autowired private ShiftBudgetAllocationRepository allocations;
    @Autowired private ShiftScheduleService scheduleService;
    @Autowired private ShiftSlotService slotService;
    @Autowired private ShiftBudgetConsumptionReconcileBatchService reconcileBatch;
    @Autowired @Qualifier("event-pool") private ThreadPoolTaskExecutor eventExecutor;
    @PersistenceContext private EntityManager em;

    @MockitoSpyBean private ShiftBudgetConsumptionRecordListener recordListener;
    @MockitoSpyBean private ShiftBudgetConsumptionCancelListener cancelListener;
    @MockitoSpyBean private ShiftBudgetConsumptionService consumptionService;
    private final AtomicInteger recordInvocations = new AtomicInteger();

    private final BlockingQueue<ShiftPublishedEvent> completedPublications = new LinkedBlockingQueue<>();
    private final BlockingQueue<ShiftScheduleClosedEvent> enteredCloses = new LinkedBlockingQueue<>();
    private final BlockingQueue<ShiftScheduleClosedEvent> completedCloses = new LinkedBlockingQueue<>();
    private final BlockingQueue<ShiftArchivedEvent> completedArchives = new LinkedBlockingQueue<>();
    private final AtomicBoolean holdNextClose = new AtomicBoolean();
    private final AtomicBoolean holdNextPublication = new AtomicBoolean();
    private final BlockingQueue<ShiftPublishedEvent> enteredPublications = new LinkedBlockingQueue<>();
    private final CountDownLatch releasePublication = new CountDownLatch(1);
    private final AtomicBoolean holdNextBatchCancellation = new AtomicBoolean();
    private final CountDownLatch batchCandidateSelected = new CountDownLatch(1);
    private final CountDownLatch releaseBatchCancellation = new CountDownLatch(1);
    private final AtomicLong batchGeneration = new AtomicLong(-1L);
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
        var consumptionTarget = AopTestUtils.getUltimateTargetObject(consumptionService);
        assertThat(mockingDetails(consumptionTarget).isSpy()).as("記録serviceの実spy").isTrue();
        doAnswer(invocation -> {
            if (scheduleId.equals(invocation.getArgument(1))) {
                recordInvocations.incrementAndGet();
            }
            return invocation.callRealMethod();
        }).when((ShiftBudgetConsumptionService) consumptionTarget)
                .recordSingleConsumption(any(), any(), any(), any(), any(), any(),
                        anyLong());
        doAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            if (scheduleId.equals(id) && holdNextBatchCancellation.compareAndSet(true, false)) {
                batchGeneration.set(invocation.getArgument(1));
                batchCandidateSelected.countDown();
                assertThat(releaseBatchCancellation.await(LIMIT.toSeconds(), TimeUnit.SECONDS))
                        .as("候補選択済み・DB取消前のbatch解放待ち").isTrue();
            }
            return invocation.callRealMethod();
        }).when((ShiftBudgetConsumptionService) consumptionTarget)
                .cancelAllForShift(any(), anyLong());
        doAnswer(invocation -> {
            ShiftPublishedEvent event = invocation.getArgument(0);
            if (!scheduleId.equals(event.getScheduleId())) return invocation.callRealMethod();
            try {
                enteredPublications.add(event);
                if (holdNextPublication.compareAndSet(true, false)) {
                    assertThat(releasePublication.await(LIMIT.toSeconds(), TimeUnit.SECONDS))
                            .as("DB操作前で保留した旧publishの有限解放待ち").isTrue();
                }
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
        doAnswer(invocation -> {
            ShiftArchivedEvent event = invocation.getArgument(0);
            if (!scheduleId.equals(event.getScheduleId())) return invocation.callRealMethod();
            try {
                return invocation.callRealMethod();
            } finally {
                completedArchives.add(event);
            }
        }).when(cancelTarget).onShiftArchived(any(ShiftArchivedEvent.class));
    }

    @AfterEach
    void cleanup() {
        releaseClose.countDown();
        releasePublication.countDown();
        releaseBatchCancellation.countDown();
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
        assertThat(cancelledCount(firstUserId)).as("旧close解放前に再公開の掃除だけで旧Aが取消済み").isEqualTo(1);
        assertThat(plannedCount(firstUserId)).as("取り外した旧担当AのPLANNEDを掃除").isZero();
        assertThat(consumedAmount()).as("旧close解放前もBだけの16000円").isEqualByComparingTo(SECOND_AMOUNT);

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

    @Test
    @Timeout(120)
    @DisplayName("AC03 公開取消後に遅延publishを配送しても消化と成功監査を作らない")
    void 公開取消後の旧公開は記録しない() throws Exception {
        stalePublicationAfter("ADJUSTING");
    }

    @Test
    @Timeout(120)
    @DisplayName("AC03 アーカイブ後に遅延publishを配送しても消化を作らない")
    void アーカイブ後の旧公開は記録しない() throws Exception {
        stalePublicationAfter("ARCHIVED");
    }

    @Test
    @Timeout(120)
    @DisplayName("AC03 論理削除後に遅延publishを配送しても消化を作らない")
    void 論理削除後の旧公開は記録しない() throws Exception {
        stalePublicationAfter("DELETE");
    }

    private void stalePublicationAfter(String status) throws Exception {
        holdNextPublication.set(true);
        scheduleService.transitionStatus(scheduleId, "PUBLISHED", firstUserId);
        ShiftPublishedEvent held = required(enteredPublications, "旧publishの実配送入口");
        assertThat(held.getPublicationGeneration()).isEqualTo(1L);
        if ("DELETE".equals(status)) {
            scheduleService.deleteSchedule(scheduleId, firstUserId);
        } else {
            scheduleService.transitionStatus(scheduleId, status, firstUserId);
        }
        if ("ARCHIVED".equals(status)) {
            assertThat(required(completedArchives, "失効archiveの実処理完了").getPublicationGeneration())
                    .isEqualTo(held.getPublicationGeneration());
        } else {
            assertThat(required(completedCloses, "失効closeの実処理完了").getPublicationGeneration())
                    .isEqualTo(held.getPublicationGeneration());
        }
        releasePublication.countDown();
        assertThat(required(completedPublications, "失効後の旧publish完了")).isSameAs(held);
        awaitEventsFinished();
        assertThat(deliveryFailure.get()).isNull();
        assertAll(
                () -> assertThat(plannedCount(firstUserId)).isZero(),
                () -> assertThat(consumedAmount()).isEqualByComparingTo(BigDecimal.ZERO),
                () -> assertThat(recordedAuditCount()).as("不受理を記録成功として監査しない").isZero());
    }

    @Test
    @Timeout(120)
    @DisplayName("AC04/14 同世代publish再配送は履歴・実額・成功監査を増やさない")
    void 同じ公開の再配送は記録成功を増やさない() throws Exception {
        ShiftPublishedEvent original = publishAndAwait();
        awaitEventsFinished();
        int audits = recordedAuditCount();
        transactionTemplate.executeWithoutResult(tx -> eventPublisher.publish(original));
        assertThat(required(completedPublications, "同世代の再配送完了")).isSameAs(original);
        awaitEventsFinished();
        assertAll(
                () -> assertThat(plannedCount(firstUserId)).isEqualTo(1),
                () -> assertThat(cancelledCount(firstUserId)).isZero(),
                () -> assertThat(consumedAmount()).isEqualByComparingTo(FIRST_AMOUNT),
                () -> assertThat(recordedAuditCount()).isEqualTo(audits));
    }

    @Test
    @Timeout(120)
    @DisplayName("AC04 新世代公開後の旧publishは新担当の記録と実額を維持する")
    void 新世代公開後の旧公開は何も記録しない() throws Exception {
        ShiftPublishedEvent oldPublication = publishAndAwait();
        closeAndAwait();
        transactionTemplate.executeWithoutResult(tx -> slots.findById(slotId).orElseThrow()
                .updateAssignedUserIds("[" + secondUserId + "]"));
        ShiftPublishedEvent current = publishAndAwait();
        assertThat(current.getPublicationGeneration()).isEqualTo(oldPublication.getPublicationGeneration() + 1);
        awaitEventsFinished();
        int audits = recordedAuditCount();
        transactionTemplate.executeWithoutResult(tx -> eventPublisher.publish(oldPublication));
        assertThat(required(completedPublications, "旧世代の再配送完了")).isSameAs(oldPublication);
        awaitEventsFinished();
        assertAll(
                () -> assertThat(plannedCount(firstUserId)).isZero(),
                () -> assertThat(plannedCount(secondUserId)).isEqualTo(1),
                () -> assertThat(consumedAmount()).isEqualByComparingTo(SECOND_AMOUNT),
                () -> assertThat(recordedAuditCount()).isEqualTo(audits));
    }

    @Test
    @Timeout(120)
    @DisplayName("AC05 削除した旧slotのPLANNEDも再公開時に掃除し、新slotだけの実額にする")
    void 削除済み旧枠の消化を再公開で回収する() throws Exception {
        publishAndAwait();
        holdNextClose.set(true);
        scheduleService.transitionStatus(scheduleId, "ADJUSTING", firstUserId);
        ShiftScheduleClosedEvent held = required(enteredCloses, "旧closeを保留");
        Long oldSlotId = slotId;
        slotService.deleteSlot(oldSlotId);
        transactionTemplate.executeWithoutResult(tx -> slotId = slots.save(
                ShiftBudgetPublicationGenerationTestFixture.slot(scheduleId, secondUserId)).getId());
        publishAndAwait();
        assertAll(
                () -> assertThat(jdbc.queryForObject("SELECT status FROM shift_budget_consumptions "
                        + "WHERE shift_id = ? AND slot_id = ?", String.class, scheduleId, oldSlotId))
                        .as("旧closeを解放する前に、削除済みslotの旧消化を回収").isEqualTo("CANCELLED"),
                () -> assertThat(plannedCount(secondUserId)).isEqualTo(1),
                () -> assertThat(consumedAmount()).isEqualByComparingTo(SECOND_AMOUNT));
        releaseClose.countDown();
        assertThat(required(completedCloses, "旧close完了")).isSameAs(held);
        awaitEventsFinished();
        assertThat(consumedAmount()).isEqualByComparingTo(SECOND_AMOUNT);
    }

    @Test
    @Timeout(120)
    @DisplayName("AC05/14 旧世代掃除の実DB減算失敗時、新しい単一記録を開始しない")
    void 旧消化掃除が失敗したら新消化を開始しない() throws Exception {
        publishAndAwait();
        assertThat(recordInvocations.get()).as("初回の実recordを観測済み").isEqualTo(1);
        holdNextClose.set(true);
        scheduleService.transitionStatus(scheduleId, "ADJUSTING", firstUserId);
        ShiftScheduleClosedEvent held = required(enteredCloses, "旧closeを保留");
        transactionTemplate.executeWithoutResult(tx -> slots.findById(slotId).orElseThrow()
                .updateAssignedUserIds("[" + secondUserId + "]"));
        String trigger = "cmp0718_cleanup_" + allocationId;
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE UPDATE ON shift_budget_allocations FOR EACH ROW "
                + "BEGIN IF OLD.id = " + allocationId + " AND NEW.consumed_amount < OLD.consumed_amount "
                + "THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '0718 cleanup rejected'; END IF; END");
        try {
            publishAndAwait();
            assertAll(
                    () -> assertThat(recordInvocations.get()).as("掃除失敗後はrecord入口へ進まない").isEqualTo(1),
                    () -> assertThat(plannedCount(firstUserId)).as("claimも同時rollback").isEqualTo(1),
                    () -> assertThat(plannedCount(secondUserId)).isZero(),
                    () -> assertThat(cancelledCount(firstUserId)).isZero(),
                    () -> assertThat(consumedAmount()).isEqualByComparingTo(FIRST_AMOUNT),
                    () -> assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shift_budget_failed_events "
                            + "WHERE organization_id = ? AND event_type = 'CONSUMPTION_RECORD' AND source_id = ?",
                            Integer.class, organizationId, scheduleId)).isEqualTo(1));
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger);
            releaseClose.countDown();
        }
        assertThat(required(completedCloses, "旧closeを正常に収束")).isSameAs(held);
        awaitEventsFinished();
        assertThat(consumedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @Timeout(120)
    @DisplayName("AC11 移行前の世代0は実listenerで記録・取消でき、最初の再公開は世代1になる")
    void 既存世代0の取消と初回再公開を対応付ける() throws Exception {
        transactionTemplate.executeWithoutResult(tx -> {
            var legacy = schedules.findById(scheduleId).orElseThrow();
            legacy.publish(firstUserId);
            eventPublisher.publish(new ShiftPublishedEvent(scheduleId, teamId, firstUserId,
                    legacy.getPublishedAt()));
        });
        assertThat(required(completedPublications, "既存世代0の実配送完了").getPublicationGeneration()).isZero();
        assertThat(jdbc.queryForObject("SELECT publication_generation FROM shift_budget_consumptions "
                + "WHERE shift_id = ? AND status = 'PLANNED'", Long.class, scheduleId)).isZero();
        ShiftScheduleClosedEvent closed = closeAndAwait();
        assertThat(closed.getPublicationGeneration()).isZero();
        assertThat(consumedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        ShiftPublishedEvent fresh = publishAndAwait();
        assertThat(fresh.getPublicationGeneration()).isEqualTo(1L);
        assertAll(
                () -> assertThat(jdbc.queryForList("SELECT publication_generation FROM shift_budget_consumptions "
                        + "WHERE shift_id = ? ORDER BY id", Long.class, scheduleId)).containsExactly(0L, 1L),
                () -> assertThat(cancelledCount(firstUserId)).isEqualTo(1),
                () -> assertThat(plannedCount(firstUserId)).isEqualTo(1),
                () -> assertThat(consumedAmount()).isEqualByComparingTo(FIRST_AMOUNT));
    }

    @Test
    @Timeout(120)
    @DisplayName("AC04/14 同じcloseの再配送では減算・履歴・取消成功監査を増やさない")
    void 同じ取消の再配送は成功件数を増やさない() throws Exception {
        publishAndAwait();
        ShiftScheduleClosedEvent closed = closeAndAwait();
        int audits = jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE team_id = ? "
                + "AND event_type = 'SHIFT_BUDGET_CONSUMPTION_CANCELLED'", Integer.class, teamId);
        transactionTemplate.executeWithoutResult(tx -> eventPublisher.publish(closed));
        assertThat(required(completedCloses, "同じcloseの実再配送完了")).isSameAs(closed);
        awaitEventsFinished();
        assertAll(
                () -> assertThat(cancelledCount(firstUserId)).isEqualTo(1),
                () -> assertThat(consumedAmount()).isEqualByComparingTo(BigDecimal.ZERO),
                () -> assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE team_id = ? "
                        + "AND event_type = 'SHIFT_BUDGET_CONSUMPTION_CANCELLED'", Integer.class, teamId))
                        .isEqualTo(audits));
    }

    @Test
    @Timeout(120)
    @DisplayName("AC12/14 batchが旧孤児を選択後に再公開されても新世代を取り消さず成功監査も増やさない")
    void 古いbatch候補の取消上限は選択時点で固定する() throws Exception {
        ShiftPublishedEvent oldPublication = publishAndAwait();
        // 配送失敗で孤児が残った既存状態を、JPA fixtureでcommitする。
        transactionTemplate.executeWithoutResult(tx -> schedules.findById(scheduleId).orElseThrow().archive());
        holdNextBatchCancellation.set(true);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var batch = worker.submit(() -> reconcileBatch.reconcileOrphanConsumptions());
            assertThat(batchCandidateSelected.await(LIMIT.toSeconds(), TimeUnit.SECONDS))
                    .as("実候補SELECT後・実取消SQL前の同期点").isTrue();
            assertThat(batchGeneration.get()).isEqualTo(oldPublication.getPublicationGeneration());
            transactionTemplate.executeWithoutResult(tx -> slots.findById(slotId).orElseThrow()
                    .updateAssignedUserIds("[" + secondUserId + "]"));
            ShiftPublishedEvent current = publishAndAwait();
            assertThat(current.getPublicationGeneration()).isEqualTo(batchGeneration.get() + 1);
            assertThat(consumedAmount()).as("batchを止めたまま新世代Bだけが記録済み")
                    .isEqualByComparingTo(SECOND_AMOUNT);
            int audits = jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE team_id = ? "
                    + "AND event_type = 'SHIFT_BUDGET_CONSUMPTION_CANCELLED'", Integer.class, teamId);
            releaseBatchCancellation.countDown();
            assertThat(batch.get(LIMIT.toSeconds(), TimeUnit.SECONDS)).isGreaterThanOrEqualTo(0);
            awaitEventsFinished();
            assertAll(
                    () -> assertThat(plannedCount(secondUserId)).isEqualTo(1),
                    () -> assertThat(consumedAmount()).isEqualByComparingTo(SECOND_AMOUNT),
                    () -> assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE team_id = ? "
                            + "AND event_type = 'SHIFT_BUDGET_CONSUMPTION_CANCELLED'", Integer.class, teamId))
                            .as("stale snapshotを取消成功件数へ付け替えない").isEqualTo(audits));
        } finally {
            releaseBatchCancellation.countDown();
            worker.shutdown();
            assertThat(worker.awaitTermination(LIMIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @Timeout(120)
    @DisplayName("AC07 公開TXのrollbackは世代・状態を戻しAFTER_COMMIT消化を発生させない")
    void 公開のrollbackでは世代も消化も進めない() {
        transactionTemplate.executeWithoutResult(tx -> {
            scheduleService.transitionStatus(scheduleId, "PUBLISHED", firstUserId);
            tx.setRollbackOnly();
        });
        awaitEventsFinished();
        assertAll(
                () -> assertThat(jdbc.queryForObject("SELECT publication_generation FROM shift_schedules WHERE id = ?",
                        Long.class, scheduleId)).isZero(),
                () -> assertThat(jdbc.queryForObject("SELECT status FROM shift_schedules WHERE id = ?",
                        String.class, scheduleId)).isEqualTo("ADJUSTING"),
                () -> assertThat(completedPublications).isEmpty(),
                () -> assertThat(recordInvocations.get()).isZero(),
                () -> assertThat(consumedAmount()).isEqualByComparingTo(BigDecimal.ZERO));
    }

    @Test
    @Timeout(120)
    @DisplayName("AC07 手動archiveは公開と同じ世代を運び実消化を取り消す")
    void 手動アーカイブは現在公開世代を取り消す() throws Exception {
        ShiftPublishedEvent publication = publishAndAwait();
        scheduleService.transitionStatus(scheduleId, "ARCHIVED", firstUserId);
        assertThat(required(completedArchives, "archive実配送完了").getPublicationGeneration())
                .isEqualTo(publication.getPublicationGeneration());
        awaitEventsFinished();
        assertAll(
                () -> assertThat(plannedCount(firstUserId)).isZero(),
                () -> assertThat(cancelledCount(firstUserId)).isEqualTo(1),
                () -> assertThat(consumedAmount()).isEqualByComparingTo(BigDecimal.ZERO));
    }

    private int recordedAuditCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE team_id = ? "
                + "AND event_type = 'SHIFT_BUDGET_CONSUMPTION_RECORDED'", Integer.class, teamId);
    }

    private ShiftPublishedEvent publishAndAwait() throws InterruptedException {
        scheduleService.transitionStatus(scheduleId, "PUBLISHED", firstUserId);
        ShiftPublishedEvent event = required(completedPublications, "公開イベントの実記録listener完了");
        assertThat(event.getScheduleId()).isEqualTo(scheduleId);
        assertThat(deliveryFailure.get()).isNull();
        return event;
    }

    private ShiftScheduleClosedEvent closeAndAwait() throws InterruptedException {
        scheduleService.transitionStatus(scheduleId, "ADJUSTING", firstUserId);
        ShiftScheduleClosedEvent event = required(completedCloses, "公開取消イベントの実listener完了");
        assertThat(event.getScheduleId()).isEqualTo(scheduleId);
        assertThat(deliveryFailure.get()).isNull();
        awaitEventsFinished();
        return event;
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
