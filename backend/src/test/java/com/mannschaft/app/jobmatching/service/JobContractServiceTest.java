package com.mannschaft.app.jobmatching.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.jobmatching.entity.JobApplicationEntity;
import com.mannschaft.app.jobmatching.entity.JobContractEntity;
import com.mannschaft.app.jobmatching.entity.JobPostingEntity;
import com.mannschaft.app.jobmatching.enums.JobApplicationStatus;
import com.mannschaft.app.jobmatching.enums.JobContractStatus;
import com.mannschaft.app.jobmatching.enums.JobPostingStatus;
import com.mannschaft.app.jobmatching.enums.RewardType;
import com.mannschaft.app.jobmatching.enums.VisibilityScope;
import com.mannschaft.app.jobmatching.enums.WorkLocationType;
import com.mannschaft.app.jobmatching.exception.JobmatchingErrorCode;
import com.mannschaft.app.jobmatching.policy.JobPolicy;
import com.mannschaft.app.jobmatching.repository.JobApplicationRepository;
import com.mannschaft.app.jobmatching.repository.JobContractRepository;
import com.mannschaft.app.jobmatching.repository.JobPostingRepository;
import com.mannschaft.app.jobmatching.service.command.ReportCompletionCommand;
import com.mannschaft.app.jobmatching.state.JobContractStateMachine;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link JobContractService} のユニットテスト。
 *
 * <p>F13.1 MVP の最重要ロジックである採用確定の排他制御と差し戻しカウンタ管理を網羅する。</p>
 *
 * <p>並列 acceptApplication テストは {@link ExecutorService}＋{@link CountDownLatch} で
 * 2 スレッドを同時起動し、モックされた Repository の挙動を「1 件目が採用済みに状態変化した状態」に
 * 遷移させることで、後続スレッドが {@link JobmatchingErrorCode#JOB_CAPACITY_FULL} を受けることを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("JobContractService 単体テスト")
class JobContractServiceTest {

    @Mock private JobApplicationRepository applicationRepository;
    @Mock private JobPostingRepository postingRepository;
    @Mock private JobContractRepository contractRepository;
    @Mock private JobContractStateMachine stateMachine;
    @Mock private JobPolicy jobPolicy;
    @Mock private JobChatService jobChatService;
    @Mock private JobNotificationService notificationService;
    @Mock private EntityManager entityManager;
    @Mock private Query nativeQuery;

    @InjectMocks private JobContractService service;

    private static final Long REQUESTER_ID = 100L;
    private static final Long WORKER_ID = 200L;
    private static final Long POSTING_ID = 1000L;
    private static final Long APPLICATION_ID = 2000L;
    private static final Long CHAT_ROOM_ID = 3000L;

    @BeforeEach
    void setUp() throws Exception {
        // @PersistenceContext は MockitoExtension による @InjectMocks では注入されないため、
        // リフレクションで EntityManager を手動注入する（private フィールド専用）。
        Field emField = JobContractService.class.getDeclaredField("entityManager");
        emField.setAccessible(true);
        emField.set(service, entityManager);

        // GET_LOCK / RELEASE_LOCK の共通スタブ（成功ケース）。
        given(entityManager.createNativeQuery(anyString())).willReturn(nativeQuery);
        given(nativeQuery.setParameter(anyInt(), any())).willReturn(nativeQuery);
        given(nativeQuery.getSingleResult()).willReturn("1");
    }

    // ---------------------------------------------------------------------
    // acceptApplication
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("acceptApplication")
    class AcceptApplicationTest {

        @Test
        @DisplayName("正常系: 応募受理で契約生成・チャットルーム割当・通知発火")
        void 正常_契約生成() {
            // Given
            JobApplicationEntity app = appliedApplication();
            JobPostingEntity posting = openPosting(1);
            given(applicationRepository.findById(APPLICATION_ID)).willReturn(Optional.of(app));
            given(postingRepository.findByIdForUpdate(POSTING_ID)).willReturn(Optional.of(posting));
            given(jobPolicy.canDecideApplication(REQUESTER_ID, posting)).willReturn(true);
            given(applicationRepository.countByJobPostingIdAndStatus(
                    POSTING_ID, JobApplicationStatus.ACCEPTED)).willReturn(0);
            given(applicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(contractRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(jobChatService.createRoomForContract(any(), any())).willReturn(CHAT_ROOM_ID);

            // When
            JobContractEntity contract = service.acceptApplication(APPLICATION_ID, REQUESTER_ID);

            // Then
            assertThat(contract.getStatus()).isEqualTo(JobContractStatus.MATCHED);
            assertThat(contract.getChatRoomId()).isEqualTo(CHAT_ROOM_ID);
            verify(jobChatService).createRoomForContract(any(), any());
            verify(notificationService).notifyMatched(any(), eq(posting));
        }

        @Test
        @DisplayName("正常系: 定員充足で求人が CLOSED に遷移する")
        void 正常_定員充足時_求人CLOSED() {
            JobApplicationEntity app = appliedApplication();
            JobPostingEntity posting = openPosting(1); // 定員1
            given(applicationRepository.findById(APPLICATION_ID)).willReturn(Optional.of(app));
            given(postingRepository.findByIdForUpdate(POSTING_ID)).willReturn(Optional.of(posting));
            given(jobPolicy.canDecideApplication(REQUESTER_ID, posting)).willReturn(true);
            given(applicationRepository.countByJobPostingIdAndStatus(
                    POSTING_ID, JobApplicationStatus.ACCEPTED)).willReturn(0);
            given(applicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(contractRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(jobChatService.createRoomForContract(any(), any())).willReturn(CHAT_ROOM_ID);

            service.acceptApplication(APPLICATION_ID, REQUESTER_ID);

            // accepted(0)+1 >= capacity(1) なので posting.close() が走り、postingRepository.save が呼ばれる
            verify(postingRepository).save(posting);
            assertThat(posting.getStatus()).isEqualTo(JobPostingStatus.CLOSED);
        }

        @Test
        @DisplayName("異常系: 権限なしで JOB_PERMISSION_DENIED")
        void 権限なし_拒否() {
            JobApplicationEntity app = appliedApplication();
            JobPostingEntity posting = openPosting(1);
            given(applicationRepository.findById(APPLICATION_ID)).willReturn(Optional.of(app));
            given(postingRepository.findByIdForUpdate(POSTING_ID)).willReturn(Optional.of(posting));
            given(jobPolicy.canDecideApplication(REQUESTER_ID, posting)).willReturn(false);

            assertThatThrownBy(() -> service.acceptApplication(APPLICATION_ID, REQUESTER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_PERMISSION_DENIED));
        }

        @Test
        @DisplayName("異常系: 応募が APPLIED 以外で JOB_APPLICATION_NOT_PENDING")
        void 処理済み応募_拒否() {
            JobApplicationEntity app = applicationWithStatus(JobApplicationStatus.ACCEPTED);
            JobPostingEntity posting = openPosting(1);
            given(applicationRepository.findById(APPLICATION_ID)).willReturn(Optional.of(app));
            given(postingRepository.findByIdForUpdate(POSTING_ID)).willReturn(Optional.of(posting));
            given(jobPolicy.canDecideApplication(REQUESTER_ID, posting)).willReturn(true);

            assertThatThrownBy(() -> service.acceptApplication(APPLICATION_ID, REQUESTER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_APPLICATION_NOT_PENDING));
        }

        @Test
        @DisplayName("異常系: 求人が OPEN でないなら JOB_NOT_OPEN")
        void 求人未公開_拒否() {
            JobApplicationEntity app = appliedApplication();
            JobPostingEntity posting = postingWithStatus(JobPostingStatus.CLOSED, 1);
            given(applicationRepository.findById(APPLICATION_ID)).willReturn(Optional.of(app));
            given(postingRepository.findByIdForUpdate(POSTING_ID)).willReturn(Optional.of(posting));
            given(jobPolicy.canDecideApplication(REQUESTER_ID, posting)).willReturn(true);

            assertThatThrownBy(() -> service.acceptApplication(APPLICATION_ID, REQUESTER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_NOT_OPEN));
        }

        @Test
        @DisplayName("異常系: 定員充足後は JOB_CAPACITY_FULL")
        void 定員充足_拒否() {
            JobApplicationEntity app = appliedApplication();
            JobPostingEntity posting = openPosting(1);
            given(applicationRepository.findById(APPLICATION_ID)).willReturn(Optional.of(app));
            given(postingRepository.findByIdForUpdate(POSTING_ID)).willReturn(Optional.of(posting));
            given(jobPolicy.canDecideApplication(REQUESTER_ID, posting)).willReturn(true);
            // 既に1名採用済み → 定員1充足
            given(applicationRepository.countByJobPostingIdAndStatus(
                    POSTING_ID, JobApplicationStatus.ACCEPTED)).willReturn(1);

            assertThatThrownBy(() -> service.acceptApplication(APPLICATION_ID, REQUESTER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_CAPACITY_FULL));
        }

        @Test
        @DisplayName("異常系: 応募が見つからない場合 JOB_APPLICATION_NOT_FOUND")
        void 応募不在_拒否() {
            given(applicationRepository.findById(APPLICATION_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.acceptApplication(APPLICATION_ID, REQUESTER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_APPLICATION_NOT_FOUND));
        }
    }

    // ---------------------------------------------------------------------
    // 並列 acceptApplication（排他制御検証）
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("acceptApplication 並列実行")
    class ConcurrentAcceptTest {

        /**
         * <p>シナリオ: 定員1の求人に対し、2人の応募（applicationA/applicationB）が同時に採用確定要求される。
         * 実装は MySQL {@code GET_LOCK} による分散排他で 2 スレッドを直列化する。テスト側はこの
         * セマンティクスを {@link ReentrantLock} で再現し、後発スレッドが先発スレッドの完了を待つ挙動を模す。</p>
         *
         * <p>1 件目成功時に {@link JobContractService#acceptApplication} は
         * 求人ステータスを {@link JobPostingStatus#CLOSED} に遷移させるため、
         * 2 件目は {@link JobmatchingErrorCode#JOB_NOT_OPEN} で拒否される
         * （{@code JOB_CAPACITY_FULL} にならないのは、close() 遷移が capacity 検査より先に到達するため）。</p>
         *
         * <p>{@link CountDownLatch} で 2 スレッドを同時スタートし、両方の完了を待ち合わせる。
         * 結果は成功 1 件・排他拒否 1 件（JOB_NOT_OPEN）となる。</p>
         */
        @RepeatedTest(value = 20, name = "{displayName} [{currentRepetition}/{totalRepetitions}]")
        @DisplayName("並列2スレッド_定員1に同時accept_排他制御で1件成功_もう1件は拒否される")
        void 並列accept_片方のみ成功() throws Exception {
            final Long applicationIdA = 2001L;
            final Long applicationIdB = 2002L;
            JobPostingEntity posting = openPosting(1);
            JobApplicationEntity appA = applicationWithIdAndStatus(
                    applicationIdA, 201L, JobApplicationStatus.APPLIED);
            JobApplicationEntity appB = applicationWithIdAndStatus(
                    applicationIdB, 202L, JobApplicationStatus.APPLIED);

            given(applicationRepository.findById(applicationIdA)).willReturn(Optional.of(appA));
            given(applicationRepository.findById(applicationIdB)).willReturn(Optional.of(appB));
            given(postingRepository.findByIdForUpdate(POSTING_ID)).willReturn(Optional.of(posting));
            given(jobPolicy.canDecideApplication(eq(REQUESTER_ID), any())).willReturn(true);
            given(applicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(contractRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(jobChatService.createRoomForContract(any(), any())).willReturn(CHAT_ROOM_ID);

            // countByJobPostingIdAndStatus の原子的カウンタ。初期0、1件成功するたび +1。
            final AtomicInteger acceptedCounter = new AtomicInteger(0);
            given(applicationRepository.countByJobPostingIdAndStatus(
                    POSTING_ID, JobApplicationStatus.ACCEPTED))
                    .willAnswer(inv -> acceptedCounter.get());

            // 1件成功時にカウンタをインクリメント（save 時点で捕捉）。
            given(applicationRepository.save(any())).willAnswer(inv -> {
                JobApplicationEntity saved = inv.getArgument(0);
                if (saved.getStatus() == JobApplicationStatus.ACCEPTED) {
                    acceptedCounter.incrementAndGet();
                }
                return saved;
            });

            // 実 DB の MySQL GET_LOCK による分散排他を ReentrantLock で再現する。
            // デフォルトの @BeforeEach スタブは GET_LOCK/RELEASE_LOCK のいずれも "1" を即時返すため、
            // 2 スレッドが count→save 区間を同時通過し、定員1に対して両方 ACCEPT される flaky が発生していた。
            // 実装（JobContractService#acquireLockOrFail）は SELECT GET_LOCK(key, timeout) を叩き、
            // 実 DB 環境では同一 key に対して 1 スレッドしか保持できない。そのセマンティクスをモックで再現する。
            final ReentrantLock lockSerializer = new ReentrantLock();
            final ThreadLocal<String> threadSql = new ThreadLocal<>();
            given(entityManager.createNativeQuery(anyString())).willAnswer(inv -> {
                threadSql.set(inv.getArgument(0));
                return nativeQuery;
            });
            given(nativeQuery.getSingleResult()).willAnswer(inv -> {
                String sql = threadSql.get();
                try {
                    if (sql != null && sql.contains("GET_LOCK")) {
                        lockSerializer.lock();
                    } else if (sql != null && sql.contains("RELEASE_LOCK")) {
                        if (lockSerializer.isHeldByCurrentThread()) {
                            lockSerializer.unlock();
                        }
                    }
                    return "1";
                } finally {
                    // ExecutorService のワーカースレッド再利用による値の持ち越し・メモリリーク防止。
                    threadSql.remove();
                }
            });

            // 2 スレッド同時起動。両スレッドが latch.await で待機 → countDown で同時スタート。
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(2);
            AtomicInteger successCount = new AtomicInteger(0);
            // 定員1 の場合、1 件目成功時に求人が CLOSED に遷移するため、後発スレッドは必ず JOB_NOT_OPEN を受ける。
            // 実装仕様（close() が capacity チェックに先立って走る）と厳密に縛ることで、
            // 将来の実装変更（close 遅延化・capacity チェック先行化など）が入った際の退行検知力を高める。
            AtomicInteger notOpenCount = new AtomicInteger(0);
            AtomicReference<Throwable> unexpectedError = new AtomicReference<>();

            Runnable runAccept = () -> { /* 各スレッドで1回採用確定を試行 */ };
            executor.submit(() -> {
                try {
                    startLatch.await();
                    service.acceptApplication(applicationIdA, REQUESTER_ID);
                    successCount.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.getErrorCode() == JobmatchingErrorCode.JOB_NOT_OPEN) {
                        notOpenCount.incrementAndGet();
                    } else {
                        unexpectedError.set(e);
                    }
                } catch (Throwable t) {
                    unexpectedError.set(t);
                } finally {
                    doneLatch.countDown();
                }
            });
            executor.submit(() -> {
                try {
                    startLatch.await();
                    service.acceptApplication(applicationIdB, REQUESTER_ID);
                    successCount.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.getErrorCode() == JobmatchingErrorCode.JOB_NOT_OPEN) {
                        notOpenCount.incrementAndGet();
                    } else {
                        unexpectedError.set(e);
                    }
                } catch (Throwable t) {
                    unexpectedError.set(t);
                } finally {
                    doneLatch.countDown();
                }
            });

            // 2 スレッド同時スタート。
            startLatch.countDown();
            assertThat(doneLatch.await(10, TimeUnit.SECONDS)).as("2 スレッドが 10 秒以内に完了").isTrue();
            executor.shutdown();

            // 想定外例外が無いこと。
            assertThat(unexpectedError.get()).as("想定外例外").isNull();
            // 成功が 1 件、JOB_NOT_OPEN が 1 件になること（排他制御 + close() 遷移が機能している証拠）。
            assertThat(successCount.get()).as("採用成功数").isEqualTo(1);
            assertThat(notOpenCount.get()).as("JOB_NOT_OPEN 発生数").isEqualTo(1);
        }
    }

    // ---------------------------------------------------------------------
    // reportCompletion / approveCompletion / rejectCompletion
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("reportCompletion")
    class ReportCompletionTest {

        @Test
        @DisplayName("正常系: MATCHED → COMPLETION_REPORTED")
        void 正常_完了報告() {
            JobContractEntity contract = contractWithStatus(JobContractStatus.MATCHED, 0);
            given(contractRepository.findById(1L)).willReturn(Optional.of(contract));
            given(jobPolicy.canReportCompletion(WORKER_ID, contract)).willReturn(true);
            given(contractRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(postingRepository.findById(POSTING_ID)).willReturn(Optional.of(openPosting(1)));

            JobContractEntity saved = service.reportCompletion(
                    1L, new ReportCompletionCommand("完了しました"), WORKER_ID);

            assertThat(saved.getStatus()).isEqualTo(JobContractStatus.COMPLETION_REPORTED);
            assertThat(saved.getCompletionReportedAt()).isNotNull();
        }

        @Test
        @DisplayName("異常系: Worker 以外が完了報告すると JOB_PERMISSION_DENIED")
        void 権限なし_拒否() {
            JobContractEntity contract = contractWithStatus(JobContractStatus.MATCHED, 0);
            given(contractRepository.findById(1L)).willReturn(Optional.of(contract));
            given(jobPolicy.canReportCompletion(REQUESTER_ID, contract)).willReturn(false);

            assertThatThrownBy(() -> service.reportCompletion(
                    1L, new ReportCompletionCommand("ng"), REQUESTER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_PERMISSION_DENIED));
        }
    }

    @Nested
    @DisplayName("approveCompletion")
    class ApproveCompletionTest {

        @Test
        @DisplayName("正常系: COMPLETION_REPORTED → COMPLETED")
        void 正常_承認() {
            JobContractEntity contract = contractWithStatus(JobContractStatus.COMPLETION_REPORTED, 0);
            given(contractRepository.findById(1L)).willReturn(Optional.of(contract));
            given(jobPolicy.canApproveCompletion(REQUESTER_ID, contract)).willReturn(true);
            given(contractRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            JobContractEntity saved = service.approveCompletion(1L, REQUESTER_ID);

            assertThat(saved.getStatus()).isEqualTo(JobContractStatus.COMPLETED);
            assertThat(saved.getCompletionApprovedAt()).isNotNull();
            verify(stateMachine).validate(JobContractStatus.COMPLETION_REPORTED, JobContractStatus.COMPLETED);
        }
    }

    @Nested
    @DisplayName("rejectCompletion")
    class RejectCompletionTest {

        @Test
        @DisplayName("正常系: 差し戻しカウントが +1 され状態は MATCHED に戻る")
        void 正常_差戻し() {
            JobContractEntity contract = contractWithStatus(JobContractStatus.COMPLETION_REPORTED, 0);
            given(contractRepository.findById(1L)).willReturn(Optional.of(contract));
            given(jobPolicy.canApproveCompletion(REQUESTER_ID, contract)).willReturn(true);
            given(contractRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            JobContractEntity saved = service.rejectCompletion(1L, "不十分です", REQUESTER_ID);

            assertThat(saved.getStatus()).isEqualTo(JobContractStatus.MATCHED);
            assertThat(saved.getRejectionCount()).isEqualTo(1);
            assertThat(saved.getLastRejectionReason()).isEqualTo("不十分です");
        }

        @Test
        @DisplayName("異常系: 差し戻し3回超過で JOB_REJECTION_LIMIT_EXCEEDED")
        void 上限超過_拒否() {
            // 既に3回差し戻し済み。4回目で上限超過判定（3+1 > 3）。
            JobContractEntity contract = contractWithStatus(JobContractStatus.COMPLETION_REPORTED, 3);
            given(contractRepository.findById(1L)).willReturn(Optional.of(contract));
            given(jobPolicy.canApproveCompletion(REQUESTER_ID, contract)).willReturn(true);

            assertThatThrownBy(() -> service.rejectCompletion(1L, "ng", REQUESTER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_REJECTION_LIMIT_EXCEEDED));
        }

        @Test
        @DisplayName("境界: 差し戻し2回目までは成功 (rejectionCount=2 → 3 に遷移)")
        void 境界_2回目成功() {
            JobContractEntity contract = contractWithStatus(JobContractStatus.COMPLETION_REPORTED, 2);
            given(contractRepository.findById(1L)).willReturn(Optional.of(contract));
            given(jobPolicy.canApproveCompletion(REQUESTER_ID, contract)).willReturn(true);
            given(contractRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            JobContractEntity saved = service.rejectCompletion(1L, "3回目", REQUESTER_ID);
            assertThat(saved.getRejectionCount()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("cancelContract")
    class CancelContractTest {

        @Test
        @DisplayName("正常系: Worker 本人によるキャンセルは許可")
        void 正常_Workerキャンセル() {
            JobContractEntity contract = contractWithStatus(JobContractStatus.MATCHED, 0);
            given(contractRepository.findById(1L)).willReturn(Optional.of(contract));
            given(contractRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            JobContractEntity saved = service.cancelContract(1L, WORKER_ID, "体調不良");

            assertThat(saved.getStatus()).isEqualTo(JobContractStatus.CANCELLED);
            assertThat(saved.getCancelledAt()).isNotNull();
        }

        @Test
        @DisplayName("異常系: 関係者でないユーザーは JOB_PERMISSION_DENIED")
        void 権限なし_拒否() {
            JobContractEntity contract = contractWithStatus(JobContractStatus.MATCHED, 0);
            given(contractRepository.findById(1L)).willReturn(Optional.of(contract));

            assertThatThrownBy(() -> service.cancelContract(1L, 9999L, "無関係ユーザー"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_PERMISSION_DENIED));
        }
    }

    // ---------------------------------------------------------------------
    // テストデータビルダ
    // ---------------------------------------------------------------------

    private JobApplicationEntity appliedApplication() {
        return applicationWithStatus(JobApplicationStatus.APPLIED);
    }

    private JobApplicationEntity applicationWithStatus(JobApplicationStatus status) {
        JobApplicationEntity entity = JobApplicationEntity.builder()
                .jobPostingId(POSTING_ID)
                .applicantUserId(WORKER_ID)
                .selfPr("test pr")
                .status(status)
                .appliedAt(LocalDateTime.now())
                .build();
        setId(entity, APPLICATION_ID);
        return entity;
    }

    private JobApplicationEntity applicationWithIdAndStatus(
            Long applicationId, Long applicantId, JobApplicationStatus status) {
        JobApplicationEntity entity = JobApplicationEntity.builder()
                .jobPostingId(POSTING_ID)
                .applicantUserId(applicantId)
                .selfPr("test pr")
                .status(status)
                .appliedAt(LocalDateTime.now())
                .build();
        setId(entity, applicationId);
        return entity;
    }

    private JobPostingEntity openPosting(int capacity) {
        return postingWithStatus(JobPostingStatus.OPEN, capacity);
    }

    private JobPostingEntity postingWithStatus(JobPostingStatus status, int capacity) {
        LocalDateTime now = LocalDateTime.now();
        JobPostingEntity entity = JobPostingEntity.builder()
                .teamId(10L)
                .createdByUserId(REQUESTER_ID)
                .title("テスト求人")
                .description("テスト内容")
                .workLocationType(WorkLocationType.ONSITE)
                .workAddress("東京都")
                .workStartAt(now.plusDays(1))
                .workEndAt(now.plusDays(1).plusHours(3))
                .rewardType(RewardType.LUMP_SUM)
                .baseRewardJpy(3000)
                .capacity(capacity)
                .applicationDeadlineAt(now.plusHours(12))
                .visibilityScope(VisibilityScope.TEAM_MEMBERS)
                .status(status)
                .build();
        setId(entity, POSTING_ID);
        return entity;
    }

    private JobContractEntity contractWithStatus(JobContractStatus status, int rejectionCount) {
        LocalDateTime now = LocalDateTime.now();
        JobContractEntity entity = JobContractEntity.builder()
                .jobPostingId(POSTING_ID)
                .jobApplicationId(APPLICATION_ID)
                .requesterUserId(REQUESTER_ID)
                .workerUserId(WORKER_ID)
                .chatRoomId(CHAT_ROOM_ID)
                .baseRewardJpy(3000)
                .workStartAt(now.plusDays(1))
                .workEndAt(now.plusDays(1).plusHours(3))
                .status(status)
                .matchedAt(now)
                .rejectionCount(rejectionCount)
                .build();
        setId(entity, 1L);
        return entity;
    }

    /**
     * BaseEntity の private id フィールドへリフレクション注入する。
     * 通常はデータベース保存で自動採番されるが、モックテストでは手動で埋める必要がある。
     */
    private void setId(Object entity, Long id) {
        try {
            Field field = entity.getClass().getSuperclass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException("id フィールド設定失敗", e);
        }
    }
}
