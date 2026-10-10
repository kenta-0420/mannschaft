package com.mannschaft.app.jobmatching;

import com.mannschaft.app.jobmatching.entity.JobApplicationEntity;
import com.mannschaft.app.jobmatching.entity.JobContractEntity;
import com.mannschaft.app.jobmatching.entity.JobPostingEntity;
import com.mannschaft.app.jobmatching.enums.JobApplicationStatus;
import com.mannschaft.app.jobmatching.enums.JobCheckInType;
import com.mannschaft.app.jobmatching.enums.JobContractStatus;
import com.mannschaft.app.jobmatching.enums.JobPostingStatus;
import com.mannschaft.app.jobmatching.enums.RewardType;
import com.mannschaft.app.jobmatching.enums.VisibilityScope;
import com.mannschaft.app.jobmatching.enums.WorkLocationType;
import com.mannschaft.app.jobmatching.event.JobNotificationEvent;
import org.springframework.context.ApplicationEventPublisher;
import com.mannschaft.app.jobmatching.repository.JobApplicationRepository;
import com.mannschaft.app.jobmatching.repository.JobCheckInRepository;
import com.mannschaft.app.jobmatching.repository.JobContractRepository;
import com.mannschaft.app.jobmatching.repository.JobPostingRepository;
import com.mannschaft.app.jobmatching.service.JobApplicationService;
import com.mannschaft.app.jobmatching.service.JobCheckInService;
import com.mannschaft.app.jobmatching.service.JobContractService;
import com.mannschaft.app.jobmatching.service.JobQrTokenService;
import com.mannschaft.app.jobmatching.service.command.ApplyCommand;
import com.mannschaft.app.jobmatching.service.command.CheckInCommand;
import com.mannschaft.app.jobmatching.service.command.ReportCompletionCommand;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;

/**
 * Issue #2997 / CMP-260827-1152 第1陣（jobmatching）— 付随通知の業務TX巻き込みの実DB検証。
 *
 * <p>対象: {@code JobApplicationService#apply} / {@code JobContractService#acceptApplication} /
 * {@code JobContractService#reportCompletion} / {@code JobCheckInService#recordCheckIn}
 * （凍結台帳キー {@code JobCheckInService#fireNotifications}）。</p>
 *
 * <h2>現行実装で落ちる理由（試練 red）</h2>
 * <p>現行は業務TXの内側で {@code JobNotificationService} → {@code NotificationService#createNotification}
 * を同期実行している。そのため (1) 業務TXがロールバックする場合でも、ロールバック前に通知生成が
 * 呼ばれてしまう（AC-A: 通知生成呼び出しが 0 件であること、に反する）。(2) 通知永続化の DB 例外が
 * {@code @Transactional} 境界を跨ぐと業務TXに rollback-only が立つ欠陥を構造的に抱える。
 * 正規形ではコミット後（AFTER_COMMIT）にのみ通知を発火する。</p>
 *
 * <h2>AC-B の障害注入の限界</h2>
 * <p>{@link NotificationService} を {@code @MockitoSpyBean} で spy し {@code createNotification} が
 * {@link DataIntegrityViolationException} を投げる形で注入している。実 MySQL 上で通知の永続化そのものを
 * 失敗させる形ではない（spy の例外は {@code NotificationService} の {@code @Transactional} プロキシを
 * 通らないため、rollback-only の伝播自体は再現しない）。業務データの確認は別接続（別TX）から行う。</p>
 *
 * <p>AC-B2（複数受信者の独立性）: jobmatching の各通知は 1 イベント = 受信者 1 名
 * （apply=Requester / accept=Worker / report=Requester / checkIn=Requester）で、複数受信者に
 * 配る経路を持たないため対象外。</p>
 *
 * <p>クラスに {@code @Transactional} を付けない（付けるとコミットが起きず AFTER_COMMIT が発火しないため）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Issue #2997 jobmatching 付随通知のTX分離（実DB）")
class JobNotificationTransactionIT extends AbstractMySqlIntegrationTest {

    @Autowired private JobApplicationService applicationService;
    @Autowired private JobContractService contractService;
    @Autowired private JobCheckInService checkInService;
    @Autowired private JobApplicationRepository applicationRepository;
    @Autowired private JobPostingRepository postingRepository;
    @Autowired private JobContractRepository contractRepository;
    @Autowired private JobCheckInRepository checkInRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private ApplicationEventPublisher eventPublisher;

    @PersistenceContext
    private EntityManager em;

    /** 通知生成を観測／失敗注入するための spy（実 Bean のまま、必要なときだけ例外を注入する）。 */
    @MockitoSpyBean private NotificationService notificationService;

    /** チェックインの QR 検証だけを差し替える（JWT 生成を避ける）。 */
    @MockitoSpyBean private JobQrTokenService qrTokenService;

    private record Fixture(Long teamId, Long requesterId, Long workerId, Long postingId) {
    }

    // ═════════════════════════════════════════════════════════════════════
    // apply
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("apply AC-A2: コミットされたら Requester に JOB_APPLIED 通知行が作られる")
    void apply_コミット後に通知が作られる() {
        Fixture f = fixture("apply-a2", 1);

        JobApplicationEntity app = applicationService.apply(f.postingId(), new ApplyCommand("よろしく"), f.workerId());

        awaitNotification(f.requesterId(), "JOB_APPLIED", app.getId());
    }

    @Test
    @DisplayName("apply AC-A: 業務TXがロールバックしたら通知生成は一度も呼ばれず、通知行も無い")
    void apply_ロールバック時は通知が作られない() {
        Fixture f = fixture("apply-a", 1);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
            applicationService.apply(f.postingId(), new ApplyCommand("よろしく"), f.workerId());
            throw new RuntimeException("強制ロールバック（AC-A検証用）");
        })).isInstanceOf(RuntimeException.class);

        assertThat(applicationRepository.findByJobPostingIdAndApplicantUserId(f.postingId(), f.workerId()))
                .as("応募行がロールバックされていること（前提）").isEmpty();
        assertThat(createNotificationCalls()).as("通知生成が呼ばれていないこと").isZero();
        assertThat(notificationsOf(f.requesterId(), "JOB_APPLIED")).isEmpty();
    }

    @Test
    @DisplayName("apply AC-B: 通知の永続化が失敗しても応募行はコミット済みで、呼び出しは正常に返る")
    void apply_通知失敗でも応募行は残る() {
        Fixture f = fixture("apply-b", 1);
        failNotificationPersistence();

        JobApplicationEntity app = applicationService.apply(f.postingId(), new ApplyCommand("よろしく"), f.workerId());

        assertThat(app.getId()).isNotNull();
        // 別接続（別TX）から確認する。
        assertThat(applicationExists(app.getId())).isTrue();
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(createNotificationCalls()).isPositive());
    }

    // ═════════════════════════════════════════════════════════════════════
    // acceptApplication
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("accept AC-A2: コミットされたら Worker に JOB_MATCHED 通知行が作られる")
    void accept_コミット後に通知が作られる() {
        Fixture f = fixture("acc-a2", 1);
        Long appId = insertApplication(f);

        JobContractEntity contract = contractService.acceptApplication(appId, f.requesterId());

        awaitNotification(f.workerId(), "JOB_MATCHED", contract.getId());
    }

    @Test
    @DisplayName("accept AC-A: 業務TXがロールバックしたら通知生成は一度も呼ばれず、契約行も無い")
    void accept_ロールバック時は通知が作られない() {
        Fixture f = fixture("acc-a", 1);
        Long appId = insertApplication(f);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
            contractService.acceptApplication(appId, f.requesterId());
            throw new RuntimeException("強制ロールバック（AC-A検証用）");
        })).isInstanceOf(RuntimeException.class);

        assertThat(contractRepository.findAll().stream()
                .filter(c -> c.getJobApplicationId().equals(appId))).isEmpty();
        assertThat(createNotificationCalls()).isZero();
        assertThat(notificationsOf(f.workerId(), "JOB_MATCHED")).isEmpty();
    }

    @Test
    @DisplayName("accept AC-B: 通知の永続化が失敗しても契約行はコミット済みで、呼び出しは正常に返る")
    void accept_通知失敗でも契約行は残る() {
        Fixture f = fixture("acc-b", 1);
        Long appId = insertApplication(f);
        failNotificationPersistence();

        JobContractEntity contract = contractService.acceptApplication(appId, f.requesterId());

        assertThat(contract.getId()).isNotNull();
        assertThat(contractExists(contract.getId())).isTrue();
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(createNotificationCalls()).isPositive());
    }

    // ═════════════════════════════════════════════════════════════════════
    // reportCompletion
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("report AC-A2: コミットされたら Requester に JOB_COMPLETION_REPORTED 通知行が作られる")
    void report_コミット後に通知が作られる() {
        Fixture f = fixture("rep-a2", 1);
        Long contractId = insertContract(f, JobContractStatus.MATCHED);

        contractService.reportCompletion(contractId, new ReportCompletionCommand("完了"), f.workerId());

        awaitNotification(f.requesterId(), "JOB_COMPLETION_REPORTED", contractId);
    }

    @Test
    @DisplayName("report AC-A: 業務TXがロールバックしたら通知生成は一度も呼ばれない")
    void report_ロールバック時は通知が作られない() {
        Fixture f = fixture("rep-a", 1);
        Long contractId = insertContract(f, JobContractStatus.MATCHED);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
            contractService.reportCompletion(contractId, new ReportCompletionCommand("完了"), f.workerId());
            throw new RuntimeException("強制ロールバック（AC-A検証用）");
        })).isInstanceOf(RuntimeException.class);

        assertThat(contractStatus(contractId)).isEqualTo(JobContractStatus.MATCHED);
        assertThat(createNotificationCalls()).isZero();
        assertThat(notificationsOf(f.requesterId(), "JOB_COMPLETION_REPORTED")).isEmpty();
    }

    @Test
    @DisplayName("report AC-B: 通知の永続化が失敗しても契約の COMPLETION_REPORTED 遷移はコミット済み")
    void report_通知失敗でも遷移は残る() {
        Fixture f = fixture("rep-b", 1);
        Long contractId = insertContract(f, JobContractStatus.MATCHED);
        failNotificationPersistence();

        contractService.reportCompletion(contractId, new ReportCompletionCommand("完了"), f.workerId());

        assertThat(contractStatus(contractId)).isEqualTo(JobContractStatus.COMPLETION_REPORTED);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(createNotificationCalls()).isPositive());
    }

    // ═════════════════════════════════════════════════════════════════════
    // recordCheckIn（凍結キー JobCheckInService#fireNotifications）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("checkIn AC-A2: コミットされたら Requester に JOB_CHECKED_IN 通知行が作られる")
    void checkIn_コミット後に通知が作られる() {
        Fixture f = fixture("ci-a2", 1);
        Long contractId = insertContract(f, JobContractStatus.MATCHED);
        stubQr(contractId, f.workerId());

        checkInService.recordCheckIn(checkInCommand(contractId, f.workerId()));

        awaitNotification(f.requesterId(), "JOB_CHECKED_IN", contractId);
    }

    @Test
    @DisplayName("checkIn AC-A: 業務TXがロールバックしたら通知生成は一度も呼ばれず、チェックイン行も無い")
    void checkIn_ロールバック時は通知が作られない() {
        Fixture f = fixture("ci-a", 1);
        Long contractId = insertContract(f, JobContractStatus.MATCHED);
        stubQr(contractId, f.workerId());

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
            checkInService.recordCheckIn(checkInCommand(contractId, f.workerId()));
            throw new RuntimeException("強制ロールバック（AC-A検証用）");
        })).isInstanceOf(RuntimeException.class);

        assertThat(checkInRepository.findByJobContractIdAndType(contractId, JobCheckInType.IN)).isEmpty();
        assertThat(createNotificationCalls()).isZero();
        assertThat(notificationsOf(f.requesterId(), "JOB_CHECKED_IN")).isEmpty();
    }

    @Test
    @DisplayName("checkIn AC-B: 通知の永続化が失敗してもチェックイン行と契約遷移はコミット済み")
    void checkIn_通知失敗でもチェックイン行は残る() {
        Fixture f = fixture("ci-b", 1);
        Long contractId = insertContract(f, JobContractStatus.MATCHED);
        stubQr(contractId, f.workerId());
        failNotificationPersistence();

        checkInService.recordCheckIn(checkInCommand(contractId, f.workerId()));

        assertThat(checkInRepository.findByJobContractIdAndType(contractId, JobCheckInType.IN)).isPresent();
        assertThat(contractStatus(contractId)).isEqualTo(JobContractStatus.IN_PROGRESS);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(createNotificationCalls()).isPositive());
    }

    // ═════════════════════════════════════════════════════════════════════
    // リスナーの CHECKED_OUT / GEO_ANOMALY 分岐（イベントを業務TXのコミットで直接発火して配送を固定する）
    // recordCheckIn 経由では geo_anomaly を立てられない（求人に緯度経度カラムが無い）ため、
    // 業務TXの中で publish → コミット、の形でリスナーを実DBで駆動する。
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("listener AC-A2: CHECKED_OUT イベントのコミットで Requester に JOB_CHECKED_OUT 通知行が作られる")
    void listener_チェックアウト通知が作られる() {
        Fixture f = fixture("lsn-out", 1);
        Long contractId = insertContract(f, JobContractStatus.IN_PROGRESS);

        publishInCommittedTx(JobNotificationEvent.checkedOut(contractId));

        awaitNotification(f.requesterId(), "JOB_CHECKED_OUT", contractId);
    }

    @Test
    @DisplayName("listener AC-A2: GEO_ANOMALY イベントのコミットで Requester に JOB_GEO_ANOMALY 通知行が作られる")
    void listener_位置乖離通知が作られる() {
        Fixture f = fixture("lsn-geo", 1);
        Long contractId = insertContract(f, JobContractStatus.IN_PROGRESS);

        publishInCommittedTx(JobNotificationEvent.geoAnomaly(contractId, 123.0));

        awaitNotification(f.requesterId(), "JOB_GEO_ANOMALY", contractId);
    }

    @Test
    @DisplayName("listener AC-B2: CHECKED_OUT と GEO_ANOMALY を同時に送る場面で、片方の永続化が失敗しても他方は届く")
    void listener_片方が失敗しても他方は届く() {
        Fixture f = fixture("lsn-both", 1);
        Long contractId = insertContract(f, JobContractStatus.IN_PROGRESS);
        // JOB_CHECKED_OUT の永続化だけ失敗させる（他の種別は実 Bean の処理を通す）。
        willThrow(new DataIntegrityViolationException("模擬通知永続化失敗（CHECKED_OUT のみ）"))
                .given(notificationService).createNotification(
                        any(), org.mockito.ArgumentMatchers.eq("JOB_CHECKED_OUT"), any(), any(), any(), any(),
                        any(), any(), any(), any(), any());

        publishInCommittedTx(JobNotificationEvent.checkedOut(contractId),
                JobNotificationEvent.geoAnomaly(contractId, 123.0));

        // 失敗させた側（CHECKED_OUT）の永続化呼び出しが実際に起きる（＝リスナーがその分岐を処理した）ことを待つ。
        // これを待たないと、CHECKED_OUT が未処理のままでも GEO_ANOMALY だけで緑になってしまう。
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(createNotificationCallsOfType("JOB_CHECKED_OUT")).isPositive());
        awaitNotification(f.requesterId(), "JOB_GEO_ANOMALY", contractId);
        // 例外は spy が createNotification の入口で投げるため、その直後にリスナーの catch で処理が完了する。
        // 限界: 本テストは spy 例外であり、実DBで @Transactional 境界を跨いだ rollback-only 伝播は再現しない。
        assertThat(notificationsOf(f.requesterId(), "JOB_CHECKED_OUT")).isEmpty();
    }

    private long createNotificationCallsOfType(String type) {
        return Mockito.mockingDetails(notificationService).getInvocations().stream()
                .filter(i -> "createNotification".equals(i.getMethod().getName()))
                .filter(i -> type.equals(i.getArgument(1)))
                .count();
    }

    private void publishInCommittedTx(JobNotificationEvent... events) {
        transactionTemplate.executeWithoutResult(tx -> {
            for (JobNotificationEvent e : events) {
                eventPublisher.publishEvent(e);
            }
        });
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private boolean applicationExists(Long id) {
        Boolean exists = transactionTemplate.execute(tx -> applicationRepository.findById(id).isPresent());
        return Boolean.TRUE.equals(exists);
    }

    private boolean contractExists(Long id) {
        Boolean exists = transactionTemplate.execute(tx -> contractRepository.findById(id).isPresent());
        return Boolean.TRUE.equals(exists);
    }

    private JobContractStatus contractStatus(Long id) {
        return transactionTemplate.execute(tx -> contractRepository.findById(id).orElseThrow().getStatus());
    }

    private void failNotificationPersistence() {
        willThrow(new DataIntegrityViolationException("模擬通知永続化失敗（AC-B検証用）"))
                .given(notificationService).createNotification(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private long createNotificationCalls() {
        return Mockito.mockingDetails(notificationService).getInvocations().stream()
                .filter(i -> "createNotification".equals(i.getMethod().getName()))
                .count();
    }

    private List<NotificationEntity> notificationsOf(Long userId, String type) {
        List<NotificationEntity> all = transactionTemplate.execute(tx ->
                notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 50)).getContent());
        return all.stream().filter(n -> type.equals(n.getNotificationType())).toList();
    }

    private void awaitNotification(Long userId, String type, Long sourceId) {
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(notificationsOf(userId, type))
                        .as("%s 通知が deny されず実際に作られていること", type)
                        .anyMatch(n -> sourceId.equals(n.getSourceId())));
    }

    private void stubQr(Long contractId, Long workerId) {
        Mockito.doReturn(new JobQrTokenService.VerifyResult(
                        null, contractId, workerId, JobCheckInType.IN, null, null))
                .when(qrTokenService).verifyShortCode(any(), any(), any());
    }

    private CheckInCommand checkInCommand(Long contractId, Long workerId) {
        return new CheckInCommand(contractId, workerId, null, "123456", JobCheckInType.IN,
                Instant.now(), false, true, null, null, null, "IT");
    }

    /** チーム・Requester(ADMIN)・Worker(MEMBER)・OPEN な求人（capacity 指定）をコミット済みで作る。 */
    private Fixture fixture(String tag, int capacity) {
        String nonce = tag + "-" + System.nanoTime();
        return transactionTemplate.execute(tx -> {
            Long teamId = insertTeam("JOBNTF " + nonce);
            Long requesterId = insertUser("jobntf-req-" + nonce + "@example.com");
            Long workerId = insertUser("jobntf-wrk-" + nonce + "@example.com");
            MembershipTestHelper.insertMembership(em, requesterId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, requesterId, "ADMIN", teamId, null);
            MembershipTestHelper.insertMembership(em, workerId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            LocalDateTime now = LocalDateTime.now();
            JobPostingEntity posting = postingRepository.save(JobPostingEntity.builder()
                    .teamId(teamId)
                    .createdByUserId(requesterId)
                    .title("JOBNTF 求人 " + nonce)
                    .description("JOBNTF 説明")
                    .workLocationType(WorkLocationType.ONSITE)
                    .workAddress("JOBNTF 会場")
                    .workStartAt(now.plusDays(3))
                    .workEndAt(now.plusDays(3).plusHours(4))
                    .rewardType(RewardType.LUMP_SUM)
                    .baseRewardJpy(3000)
                    .capacity(capacity)
                    .applicationDeadlineAt(now.plusDays(2))
                    .visibilityScope(VisibilityScope.TEAM_MEMBERS)
                    .status(JobPostingStatus.OPEN)
                    .build());
            return new Fixture(teamId, requesterId, workerId, posting.getId());
        });
    }

    private Long insertApplication(Fixture f) {
        return transactionTemplate.execute(tx -> applicationRepository.save(JobApplicationEntity.builder()
                .jobPostingId(f.postingId())
                .applicantUserId(f.workerId())
                .selfPr("JOBNTF 自己PR")
                .status(JobApplicationStatus.APPLIED)
                .appliedAt(LocalDateTime.now())
                .build()).getId());
    }

    private Long insertContract(Fixture f, JobContractStatus status) {
        return transactionTemplate.execute(tx -> {
            Long appId = applicationRepository.save(JobApplicationEntity.builder()
                    .jobPostingId(f.postingId())
                    .applicantUserId(f.workerId())
                    .status(JobApplicationStatus.ACCEPTED)
                    .appliedAt(LocalDateTime.now())
                    .build()).getId();
            LocalDateTime now = LocalDateTime.now();
            return contractRepository.save(JobContractEntity.builder()
                    .jobPostingId(f.postingId())
                    .jobApplicationId(appId)
                    .requesterUserId(f.requesterId())
                    .workerUserId(f.workerId())
                    .baseRewardJpy(3000)
                    .workStartAt(now.plusDays(3))
                    .workEndAt(now.plusDays(3).plusHours(4))
                    .status(status)
                    .matchedAt(now)
                    .rejectionCount(0)
                    .build()).getId();
        });
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
                                + "VALUES (:email, 'JOBNTF', 'テスト', 'JOBNTF テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
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
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
