package com.mannschaft.app.shiftbudget;

import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.ShiftPeriodType;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.shift.service.ShiftScheduleService;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetConsumptionEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepository;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetAllocationService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * CMP-260909-1445 — 手動アーカイブ / 論理削除 / 公開取消でシフト予算の消化が取り消されることの実 DB 検証。
 *
 * <h2>何を実証するテストか</h2>
 * <p>是正前、{@code ShiftArchivedEvent} を publish していたのは
 * {@code ShiftAutoArchiveBatchService} ただ 1 箇所であり、UI/API 経由の
 * {@code ShiftScheduleService#transitionStatus(ARCHIVED)} は
 * {@code entity.archive()} を呼ぶだけでイベントを出さなかった。その結果</p>
 * <ul>
 *   <li>{@code ShiftBudgetConsumptionCancelListener} が走らず PLANNED 消化が残り続け、
 *       当該 allocation の論理削除が恒久的に 409 {@code SHIFT_BUDGET_012} で拒否される</li>
 *   <li>{@code ShiftArchivedToTodoCancelListener} も走らず Todo が OPEN のまま残る</li>
 *   <li>OPEN の変更依頼がバッチ経路でだけ WITHDRAWN 化され、手動経路では残る</li>
 * </ul>
 * <p>さらに {@code deleteSchedule}（論理削除）と PUBLISHED からの後戻り遷移（公開取消）も
 * 同型の穴だった。</p>
 *
 * <h2>クラスに {@code @Transactional} を付けない理由</h2>
 * <p>消化の取消は {@code AFTER_COMMIT} + {@code @Async("event-pool")} で起きる。テスト全体を
 * トランザクションで包むとコミットが発生せずリスナーが一度も発火しないまま
 * 「取り消されていない」という観測しか得られない偽の緑／赤になる。
 * フィクスチャ投入・検証読み取りは {@link TransactionTemplate} / {@link JdbcTemplate} で
 * 明示的にコミットする（金型: {@code ShiftBudgetThresholdAlertNotificationBoundaryIT}）。</p>
 *
 * <p>境界を実際に跨いだことは {@link #ロールバックしたら消化は取り消されない()} が対照として示す。
 * 業務トランザクションが巻き戻ったときに消化が PLANNED のまま残ることを確認することで、
 * 他の検体の緑が「コミット後に本当にリスナーが走った」結果であることを裏づける。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
// F08.7 のグローバルフラグを明示的に立てる。src/test/resources/application-test.yml が
// テストクラスパス側で優先されるため、src/main/resources/application-test.yml に書かれた
// feature.shift-budget.enabled: true は統合テストへ届かない（red 実行時に
// deleteAllocation が FEATURE_DISABLED を投げて初めて判明した）。
// これを立てないと ShiftBudgetConsumptionCancelListener がフラグ OFF で早期 return し、
// 「イベントは出ているのに消化が取り消されない」偽の赤になる。
@TestPropertySource(properties = "feature.shift-budget.enabled=true")
@DisplayName("CMP-260909-1445 手動アーカイブ・削除・公開取消の予算消化取消（実DB）")
class ShiftManualArchiveConsumptionCancelIT extends AbstractMySqlIntegrationTest {

    private static final BigDecimal CONSUMED = new BigDecimal("10000.00");
    private static final BigDecimal ALLOCATED = new BigDecimal("500000.00");

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ShiftScheduleService shiftScheduleService;

    @Autowired
    private ShiftBudgetAllocationService allocationService;

    @Autowired
    private ShiftBudgetConsumptionRepository consumptionRepository;

    @Autowired
    private ShiftBudgetAllocationRepository allocationRepository;

    @Autowired
    private ShiftScheduleRepository scheduleRepository;

    @Autowired
    private ShiftSlotRepository slotRepository;

    @Autowired
    private FeatureFlagRepository featureFlagRepository;

    @Autowired
    private CacheManager cacheManager;

    @PersistenceContext
    private EntityManager em;

    private Long orgId;
    private Long teamId;
    private Long actorId;
    private Long scheduleId;
    private Long slotId;
    private Long allocationId;
    private Long consumptionId;
    private Long todoId;
    private Long changeRequestId;

    @BeforeEach
    void setUp() {
        FeatureFlagTestSupport.enable(featureFlagRepository, cacheManager, "FEATURE_SHIFT_ENABLED");
        String nonce = nonce();

        transactionTemplate.executeWithoutResult(tx -> {
            orgId = insertOrganization("CMP1445 組織 " + nonce);
            teamId = insertTeam("CMP1445 チーム " + nonce);
            insertTeamOrgMembership(teamId, orgId);

            actorId = insertUser("cmp1445-admin-" + nonce + "@example.com");
            MembershipTestHelper.insertMembership(em, actorId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, actorId, "ADMIN", teamId, null);
            // allocation の論理削除は BUDGET_ADMIN 必須。SYSTEM_ADMIN で短絡させる（AC-04 用）
            MembershipTestHelper.insertUserRole(em, actorId, "SYSTEM_ADMIN", null, null);
            em.flush();
        });

        seedSchedule();
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    /** シフト・スロット・割当・消化・Todo・変更依頼の一式を張る。 */
    private void seedSchedule() {
        transactionTemplate.executeWithoutResult(tx -> {
            ShiftScheduleEntity schedule = scheduleRepository.save(ShiftScheduleEntity.builder()
                    .teamId(teamId)
                    .title("CMP1445 シフト " + nonce())
                    .periodType(ShiftPeriodType.WEEKLY)
                    .startDate(LocalDate.of(2026, 6, 1))
                    .endDate(LocalDate.of(2026, 6, 7))
                    .status(ShiftScheduleStatus.PUBLISHED)
                    .publishedAt(LocalDateTime.of(2026, 5, 20, 10, 0))
                    .publishedBy(actorId)
                    .createdBy(actorId)
                    .build());
            scheduleId = schedule.getId();

            ShiftSlotEntity slot = slotRepository.save(ShiftSlotEntity.builder()
                    .scheduleId(scheduleId)
                    .slotDate(LocalDate.of(2026, 6, 1))
                    .startTime(LocalTime.of(9, 0))
                    .endTime(LocalTime.of(18, 0))
                    .requiredCount(1)
                    .assignedUserIds("[" + actorId + "]")
                    .build());
            slotId = slot.getId();

            ShiftBudgetAllocationEntity allocation = allocationRepository.save(
                    ShiftBudgetAllocationEntity.builder()
                            .organizationId(orgId)
                            .teamId(teamId)
                            .fiscalYearId(9001L)
                            .budgetCategoryId(9002L)
                            .periodStart(LocalDate.of(2026, 6, 1))
                            .periodEnd(LocalDate.of(2026, 6, 30))
                            .allocatedAmount(ALLOCATED)
                            .consumedAmount(CONSUMED)
                            .confirmedAmount(BigDecimal.ZERO)
                            .currency("JPY")
                            .createdBy(actorId)
                            .version(0L)
                            .build());
            allocationId = allocation.getId();

            ShiftBudgetConsumptionEntity consumption = consumptionRepository.save(
                    ShiftBudgetConsumptionEntity.builder()
                            .allocationId(allocationId)
                            .shiftId(scheduleId)
                            .slotId(slotId)
                            .userId(actorId)
                            .hourlyRateSnapshot(new BigDecimal("1250.00"))
                            .hours(new BigDecimal("8.00"))
                            .amount(CONSUMED)
                            .currency("JPY")
                            .status(ShiftBudgetConsumptionStatus.PLANNED)
                            .recordedAt(LocalDateTime.now())
                            .build());
            consumptionId = consumption.getId();

            todoId = insertShiftLinkedTodo();
            changeRequestId = insertOpenChangeRequest();
        });
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-01 / AC-02 / AC-03 / AC-11: 手動アーカイブの副作用
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-01 手動アーカイブのコミット後、PLANNED 消化が CANCELLED になり consumed_amount が減算される")
    void 手動アーカイブで消化が取り消される() {
        archive();

        awaitConsumptionStatus("CANCELLED");
        assertThat(consumedAmount())
                .as("消化取消と対で allocation.consumed_amount が減算されること")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(cancelReason())
                .as("取消理由が記録されること（監査証跡）")
                .isNotNull();
    }

    @Test
    @DisplayName("AC-02 手動アーカイブのコミット後、紐づく自動作成 Todo が CANCELLED になる")
    void 手動アーカイブでTodoが取り消される() {
        archive();

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(todoStatus())
                        .as("バッチ経路と同じく ShiftArchivedEvent の購読者が走ること")
                        .isEqualTo("CANCELLED"));
    }

    @Test
    @DisplayName("AC-03 手動アーカイブで OPEN の変更依頼が WITHDRAWN 化される（バッチ経路と副作用が一致する）")
    void 手動アーカイブで変更依頼が取り下げられる() {
        archive();

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(changeRequestStatus())
                        .as("ShiftAutoArchiveBatchService と同じ副作用が手動経路にも要る")
                        .isEqualTo("WITHDRAWN"));
    }

    @Test
    @DisplayName("AC-11 消化取消の監査ログに操作者IDが載る（バッチは null、手動は操作者）")
    void 手動アーカイブの監査ログに操作者が載る() {
        archive();

        awaitConsumptionStatus("CANCELLED");
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(latestCancelAuditUserId())
                        .as("誰が消化を取り消したのか追えないと経理監査に応えられない")
                        .isEqualTo(actorId));
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-04: allocation の削除が 409 SHIFT_BUDGET_012 にならない
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-04 手動アーカイブ後は allocation を削除できる（SHIFT_BUDGET_012 で恒久ブロックされない）")
    void 手動アーカイブ後は割当を削除できる() {
        setAuth(actorId);
        assertThatThrownBy(() -> allocationService.deleteAllocation(orgId, allocationId))
                .as("前提: PLANNED 消化が残っている間は 409 SHIFT_BUDGET_012 で拒否される")
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode().getCode()).isEqualTo("SHIFT_BUDGET_012"));

        archive();
        awaitConsumptionStatus("CANCELLED");

        setAuth(actorId);
        assertThatCode(() -> allocationService.deleteAllocation(orgId, allocationId))
                .as("アーカイブ済みシフトの消化が残る限り割当は永久に削除できない（本欠陥の実害）")
                .doesNotThrowAnyException();
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-05 / AC-08: 冪等性（二重取消・二重減算をしない）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-05 既に ARCHIVED のシフトを再度 ARCHIVED にしても二重減算しない")
    void 再アーカイブは冪等() {
        archive();
        awaitConsumptionStatus("CANCELLED");

        archive();

        assertStableConsumedAmount();
    }

    @Test
    @DisplayName("AC-08 アーカイブ→削除でも consumed_amount が二重減算されない")
    void アーカイブしてから削除しても二重減算しない() {
        archive();
        awaitConsumptionStatus("CANCELLED");

        delete();

        assertStableConsumedAmount();
    }

    @Test
    @DisplayName("AC-08 削除→アーカイブでも consumed_amount が二重減算されない（削除済みは遷移不可のまま）")
    void 削除してからアーカイブしても二重減算しない() {
        delete();
        awaitConsumptionStatus("CANCELLED");

        // 論理削除済みスケジュールは findScheduleOrThrow が 404 を返すため、そもそも
        // ARCHIVED へ遷移できない。これは仕様どおりであり、二重取消の経路が存在しないことを意味する。
        // 「削除→アーカイブ」を成立させるために削除済みを遷移可能にするのは本末転倒なので、
        // 期待値ではなく前提のほうを実装に合わせて固定する。
        assertThatThrownBy(this::archive)
                .as("論理削除済みシフトを再びアーカイブできてしまうと、消化の二重取消経路が生まれる")
                .isInstanceOf(BusinessException.class);

        // 再度の論理削除も同じ理由で 404。残高が動かないことを一定時間観測する。
        assertThatThrownBy(this::delete).isInstanceOf(BusinessException.class);
        assertStableConsumedAmount();
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-07: 論理削除
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-07 deleteSchedule（論理削除）でも PLANNED 消化が CANCELLED になる")
    void 論理削除で消化が取り消される() {
        delete();

        awaitConsumptionStatus("CANCELLED");
        assertThat(consumedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-13: PUBLISHED からの後戻り遷移（公開取消）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-13 PUBLISHED から ADJUSTING への後戻り（公開取消）でも消化が残らない")
    void 公開取消でも消化が残らない() {
        // ShiftScheduleEntity#startAdjusting には遷移ガードが無く、PUBLISHED からの後戻りは
        // 実際に成立する（entity のメソッドは status を無条件に上書きするだけ）。
        // 成立する以上、公開時に積んだ消化を置き去りにしてはならない。
        transition("ADJUSTING");

        assertThat(scheduleStatus())
                .as("後戻り遷移が実際に成立していること（この前提が崩れたら本検体の意味が変わる）")
                .isEqualTo("ADJUSTING");
        awaitConsumptionStatus("CANCELLED");
        assertThat(consumedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ═══════════════════════════════════════════════════════════════
    // 境界の対照（AFTER_COMMIT を本当に跨いだことの裏づけ）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("境界: 業務トランザクションがロールバックしたら消化は取り消されない")
    void ロールバックしたら消化は取り消されない() {
        setAuth(actorId);
        assertThatThrownBy(() -> transactionTemplate.execute(tx -> {
            shiftScheduleService.transitionStatus(scheduleId, "ARCHIVED", actorId);
            throw new IllegalStateException("CMP-260909-1445: 業務側の失敗を模す");
        })).isInstanceOf(IllegalStateException.class);

        // 一定時間「取り消されないままである」ことを観測する。
        // 単発の 1 回アサートは非同期リスナーが動き出す前に通ってしまい何も検証しない。
        await().during(Duration.ofSeconds(2)).atMost(8, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(consumptionStatus())
                    .as("コミットされていない以上、AFTER_COMMIT の取消は走ってはならない")
                    .isEqualTo("PLANNED");
            assertThat(consumedAmount()).isEqualByComparingTo(CONSUMED);
        });
    }

    // ═══════════════════════════════════════════════════════════════
    // ヘルパー
    // ═══════════════════════════════════════════════════════════════

    private void archive() {
        transition("ARCHIVED");
    }

    private void transition(String status) {
        setAuth(actorId);
        shiftScheduleService.transitionStatus(scheduleId, status, actorId);
    }

    private void delete() {
        setAuth(actorId);
        shiftScheduleService.deleteSchedule(scheduleId, actorId);
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private void awaitConsumptionStatus(String expected) {
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(consumptionStatus()).isEqualTo(expected));
    }

    /**
     * 追加操作の後に consumed_amount が動かないことを一定時間観測する。
     * 二重減算はマイナス値として現れるため、単発の等値アサートでは非同期処理の到達前に
     * 通り抜けてしまう。
     */
    private void assertStableConsumedAmount() {
        await().during(Duration.ofSeconds(2)).atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(consumedAmount())
                        .as("二重取消・二重減算が起きればここがマイナスに振れる")
                        .isEqualByComparingTo(BigDecimal.ZERO));
    }

    private String consumptionStatus() {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM shift_budget_consumptions WHERE id = ?", String.class, consumptionId);
    }

    private String cancelReason() {
        return jdbcTemplate.queryForObject(
                "SELECT cancel_reason FROM shift_budget_consumptions WHERE id = ?",
                String.class, consumptionId);
    }

    private BigDecimal consumedAmount() {
        return jdbcTemplate.queryForObject(
                "SELECT consumed_amount FROM shift_budget_allocations WHERE id = ?",
                BigDecimal.class, allocationId);
    }

    private String scheduleStatus() {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM shift_schedules WHERE id = ?", String.class, scheduleId);
    }

    private String todoStatus() {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM todos WHERE id = ?", String.class, todoId);
    }

    private String changeRequestStatus() {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM shift_change_requests WHERE id = ?", String.class, changeRequestId);
    }

    /**
     * 当該チームの消化取消の監査ログのうち最新 1 件の {@code user_id} を返す。
     *
     * <p>チームはテストごとに新規採番しているため、他検体の監査ログとは混ざらない。
     * {@code metadata} の JSON 表記に依存した LIKE 照合は、書式が変わると
     * 「照合できないのに 0 件だから緑／赤」と読み違える原因になるので使わない。
     * まだ 1 件も無い場合は null を返し、{@code await} がリトライできるようにする。</p>
     */
    private Long latestCancelAuditUserId() {
        List<Long> rows = jdbcTemplate.queryForList(
                "SELECT user_id FROM audit_logs "
                        + "WHERE event_type = 'SHIFT_BUDGET_CONSUMPTION_CANCELLED' "
                        + "  AND team_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, teamId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String nonce() {
        return String.valueOf(System.nanoTime());
    }

    private Long insertShiftLinkedTodo() {
        em.createNativeQuery(
                        "INSERT INTO todos (scope_type, scope_id, milestone_locked, position, depth, "
                                + "title, status, priority, linked_schedule_id, linked_shift_slot_id, "
                                + "progress_rate, progress_manual, created_by, sort_order, created_at, updated_at) "
                                + "VALUES ('TEAM', :teamId, 0, 0, 0, "
                                + "'CMP1445 シフト由来 Todo', 'OPEN', 'MEDIUM', :scheduleId, :slotId, "
                                + "0, 0, :userId, 0, NOW(), NOW())")
                .setParameter("teamId", teamId)
                .setParameter("scheduleId", scheduleId)
                .setParameter("slotId", slotId)
                .setParameter("userId", actorId)
                .executeUpdate();
        em.flush();
        return ((Number) em.createNativeQuery(
                        "SELECT id FROM todos WHERE linked_schedule_id = :scheduleId ORDER BY id DESC LIMIT 1")
                .setParameter("scheduleId", scheduleId)
                .getSingleResult()).longValue();
    }

    private Long insertOpenChangeRequest() {
        em.createNativeQuery(
                        "INSERT INTO shift_change_requests (schedule_id, slot_id, request_type, status, "
                                + "requested_by, reason, version, created_at, updated_at) "
                                + "VALUES (:scheduleId, :slotId, 'INDIVIDUAL_SWAP', 'OPEN', :userId, "
                                + "'CMP1445 変更依頼', 0, NOW(), NOW())")
                .setParameter("scheduleId", scheduleId)
                .setParameter("slotId", slotId)
                .setParameter("userId", actorId)
                .executeUpdate();
        em.flush();
        return ((Number) em.createNativeQuery(
                        "SELECT id FROM shift_change_requests WHERE schedule_id = :scheduleId ORDER BY id DESC LIMIT 1")
                .setParameter("scheduleId", scheduleId)
                .getSingleResult()).longValue();
    }

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        em.flush();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        em.flush();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private void insertTeamOrgMembership(Long team, Long org) {
        em.createNativeQuery(
                        "INSERT INTO team_org_memberships (team_id, organization_id, status, invited_at, created_at) "
                                + "VALUES (:tid, :oid, 'ACTIVE', NOW(), NOW())")
                .setParameter("tid", team)
                .setParameter("oid", org)
                .executeUpdate();
        em.flush();
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'CMP1445', 'テスト', 'CMP1445 管理者', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        em.flush();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }
}
