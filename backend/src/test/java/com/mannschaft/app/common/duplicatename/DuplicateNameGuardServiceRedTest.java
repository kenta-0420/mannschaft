package com.mannschaft.app.common.duplicatename;

import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CMP-260901-1538 柱③-A「組織・チーム名称の重複許可」受け入れ条件テスト（試練で red として設置し、
 * 出陣で {@link DuplicateNameGuardServiceImpl} を実装して green 化した）。検分第2巡是正
 * （専用 JDBC 接続方式によるロック管理）を反映した第3版。
 *
 * <p>{@link DuplicateNameGuardService} の作成前チェック中核ロジックの最終挙動を直接検査する。
 * {@code dataSource.getConnection()} が返す専用接続 {@code connection} 上で
 * {@code GET_LOCK}/{@code RELEASE_LOCK} を発行する契約を、素の JDBC モック（Connection/
 * PreparedStatement/ResultSet）で検証する。</p>
 *
 * <h2>AC ↔ テスト対応</h2>
 * <ul>
 *   <li>AC-01 同名候補が存在しない場合は例外を投げず createAction を実行して結果を返す
 *       → {@link #ac01_noCandidatesRunsCreateActionAndReturnsResult()}</li>
 *   <li>AC-02 同名候補が存在し confirmDuplicate=false の場合は 409（候補一覧＋fingerprint）を投げ、
 *       createAction は実行しない → {@link #ac02_candidatesExistWithoutConfirmationThrows409()}</li>
 *   <li>AC-03/P1-1 PRIVATE 候補は id・名称ともに応答へ含めず件数のみに畳む
 *       → {@link #ac03_privateCandidateHiddenFromResponseButCounted()}</li>
 *   <li>AC-06 confirmDuplicate=true かつ fingerprint 一致なら createAction を実行して続行できる
 *       → {@link #ac06_confirmedWithValidFingerprintRunsCreateAction()}</li>
 *   <li>AC-07 確認後に候補集合が変化（fingerprint不一致）していれば再度 409
 *       → {@link #ac07_fingerprintMismatchAfterConfirmationThrows409Again()}</li>
 *   <li>AC-08 fingerprint 未指定で confirmDuplicate=true を送ると 409（検証不能）
 *       → {@link #ac08_confirmedWithoutFingerprintThrows409()}</li>
 *   <li>AC-09 候補判定は候補供給コールバック（アドバイザリロック保持中）を必ず呼んで得た結果にのみ基づく
 *       → {@link #ac09_candidateSupplierIsConsultedOnEveryCall()}</li>
 *   <li>P1-2 GET_LOCK と RELEASE_LOCK が同一の専用接続・同一キーで対になって呼ばれる
 *       → {@link #p1_2_lockIsAcquiredAndReleasedOnSameDedicatedConnection()}</li>
 *   <li>P1-2 GET_LOCK がタイムアウトしたら DUPNAME_002（409）を投げ、候補問い合わせも
 *       createAction も実行しない。RELEASE_LOCK は呼ばれないが専用接続は close する
 *       → {@link #p1_2_lockTimeoutThrowsDupname002AndSkipsCandidateSupplierAndCreateAction()}</li>
 *   <li>確認要求例外発生時もロックは即座に解放・専用接続は close される（何も作成していないため）
 *       → {@link #lockIsReleasedAndConnectionClosedWhenConfirmationRequiredExceptionThrown()}</li>
 *   <li>第2巡 P1-1 是正: トランザクションが存在する場合、作成成功後も専用接続は
 *       即座には close されず、{@code afterCompletion} まで解放が遅延される
 *       → {@link #r2p1_1_lockReleaseIsDeferredUntilTransactionAfterCompletionWhenTransactionActive()}</li>
 *   <li>第2巡 P1-1 是正: トランザクションが存在しない場合は createAction 完了直後に即時解放される
 *       → {@link #r2p1_1_lockIsReleasedImmediatelyWhenNoTransactionActive()}</li>
 *   <li>第2巡 P1-2 是正: RELEASE_LOCK 自体が例外を投げても専用接続の close は必ず呼ばれる
 *       → {@link #r2p1_2_connectionIsClosedEvenWhenReleaseLockThrows()}</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DuplicateNameGuardServiceRedTest {

    @Mock
    private DuplicateNameFingerprintService fingerprintService;

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    @Mock
    private PreparedStatement getLockStatement;

    @Mock
    private PreparedStatement releaseLockStatement;

    @Mock
    private ResultSet getLockResultSet;

    @InjectMocks
    private DuplicateNameGuardServiceImpl guardService;

    @AfterEach
    void clearTransactionSynchronization() {
        // 第2巡是正テストで initSynchronization() した場合の後始末（他テストへ状態を持ち越さない）。
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** GET_LOCK は常に成功（1）、RELEASE_LOCK も常に成功する既定スタブ。 */
    private void stubLockAlwaysAcquired() throws SQLException {
        lenient().when(dataSource.getConnection()).thenReturn(connection);
        lenient().when(connection.prepareStatement(eq("SELECT GET_LOCK(?, ?)"))).thenReturn(getLockStatement);
        lenient().when(getLockStatement.executeQuery()).thenReturn(getLockResultSet);
        lenient().when(getLockResultSet.next()).thenReturn(true);
        lenient().when(getLockResultSet.getInt(1)).thenReturn(1);
        lenient().when(getLockResultSet.wasNull()).thenReturn(false);
        lenient().when(connection.prepareStatement(eq("SELECT RELEASE_LOCK(?)"))).thenReturn(releaseLockStatement);
    }

    @Test
    @DisplayName("AC-01: 同名候補が無ければ例外を投げず createAction を実行して結果を返す")
    void ac01_noCandidatesRunsCreateActionAndReturnsResult() throws SQLException {
        stubLockAlwaysAcquired();
        AtomicInteger createActionCallCount = new AtomicInteger();

        String result = guardService.checkForCreateAndRun(
                DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 1L,
                false, null, List::of,
                () -> {
                    createActionCallCount.incrementAndGet();
                    return "created";
                });

        assertThat(result).isEqualTo("created");
        assertThat(createActionCallCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-02: 未確認で同名候補があれば 409（候補一覧＋fingerprint）を投げ createAction は実行しない")
    void ac02_candidatesExistWithoutConfirmationThrows409() throws SQLException {
        stubLockAlwaysAcquired();
        when(fingerprintService.issue(any(), any(), any(), any())).thenReturn("dummy-fp");
        AtomicInteger createActionCallCount = new AtomicInteger();

        assertThatThrownBy(() -> guardService.checkForCreateAndRun(
                DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 1L,
                false, null,
                () -> List.of(new DuplicateNameCandidate("10", true, "サンプル組織")),
                () -> {
                    createActionCallCount.incrementAndGet();
                    return "created";
                }))
                .isInstanceOf(DuplicateNameConfirmationRequiredException.class)
                .satisfies(ex -> {
                    DuplicateNameConfirmationDetails details =
                            ((DuplicateNameConfirmationRequiredException) ex).getDetails();
                    assertThat(details.fingerprint()).isEqualTo("dummy-fp");
                    assertThat(details.visibleCandidates()).hasSize(1);
                    assertThat(details.visibleCandidates().get(0).id()).isEqualTo("10");
                    assertThat(details.visibleCandidates().get(0).name()).isEqualTo("サンプル組織");
                    assertThat(details.hiddenCandidateCount()).isZero();
                });
        assertThat(createActionCallCount.get()).isZero();
    }

    @Test
    @DisplayName("AC-03/P1-1: PRIVATE 候補は id・名称ともに応答へ含めず件数のみに畳む")
    void ac03_privateCandidateHiddenFromResponseButCounted() throws SQLException {
        stubLockAlwaysAcquired();
        when(fingerprintService.issue(any(), any(), any(), any())).thenReturn("dummy-fp");

        assertThatThrownBy(() -> guardService.checkForCreateAndRun(
                DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 1L,
                false, null,
                () -> List.of(new DuplicateNameCandidate("20", false, null)),
                () -> "created"))
                .isInstanceOf(DuplicateNameConfirmationRequiredException.class)
                .satisfies(ex -> {
                    DuplicateNameConfirmationDetails details =
                            ((DuplicateNameConfirmationRequiredException) ex).getDetails();
                    // P1-1 是正: PRIVATE 候補の id は応答にまったく現れない
                    // （visibleCandidates が空＝id "20" を含む要素が存在しない）。
                    assertThat(details.visibleCandidates()).isEmpty();
                    assertThat(details.hiddenCandidateCount()).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("AC-03/P1-1: PUBLIC と PRIVATE が混在する場合、可視のみ開示し非公開は件数に畳む")
    void ac03_mixedVisibilityAggregatesHiddenCountSeparately() throws SQLException {
        stubLockAlwaysAcquired();
        when(fingerprintService.issue(any(), any(), any(), any())).thenReturn("dummy-fp");

        assertThatThrownBy(() -> guardService.checkForCreateAndRun(
                DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 1L,
                false, null,
                () -> List.of(
                        new DuplicateNameCandidate("10", true, "サンプル組織"),
                        new DuplicateNameCandidate("20", false, null),
                        new DuplicateNameCandidate("21", false, null)),
                () -> "created"))
                .isInstanceOf(DuplicateNameConfirmationRequiredException.class)
                .satisfies(ex -> {
                    DuplicateNameConfirmationDetails details =
                            ((DuplicateNameConfirmationRequiredException) ex).getDetails();
                    assertThat(details.visibleCandidates()).hasSize(1);
                    assertThat(details.visibleCandidates().get(0).id()).isEqualTo("10");
                    assertThat(details.hiddenCandidateCount()).isEqualTo(2);
                });
    }

    @Test
    @DisplayName("AC-06: confirmDuplicate=true かつ fingerprint 一致なら createAction を実行して続行できる")
    void ac06_confirmedWithValidFingerprintRunsCreateAction() throws SQLException {
        stubLockAlwaysAcquired();
        when(fingerprintService.verify(any(), any(), any(), any(), any())).thenReturn(true);
        AtomicInteger createActionCallCount = new AtomicInteger();

        String result = guardService.checkForCreateAndRun(
                DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 1L,
                true, "valid-fp",
                () -> List.of(new DuplicateNameCandidate("10", true, "サンプル組織")),
                () -> {
                    createActionCallCount.incrementAndGet();
                    return "created";
                });

        assertThat(result).isEqualTo("created");
        assertThat(createActionCallCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-07: 確認後に候補集合が変化（fingerprint不一致）していれば再度 409")
    void ac07_fingerprintMismatchAfterConfirmationThrows409Again() throws SQLException {
        stubLockAlwaysAcquired();
        when(fingerprintService.verify(any(), any(), any(), any(), any())).thenReturn(false);
        when(fingerprintService.issue(any(), any(), any(), any())).thenReturn("new-fp");

        assertThatThrownBy(() -> guardService.checkForCreateAndRun(
                DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 1L,
                true, "stale-fp",
                () -> List.of(
                        new DuplicateNameCandidate("10", true, "サンプル組織"),
                        new DuplicateNameCandidate("11", true, "サンプル組織")),
                () -> "created"))
                .isInstanceOf(DuplicateNameConfirmationRequiredException.class)
                .satisfies(ex -> {
                    DuplicateNameConfirmationDetails details =
                            ((DuplicateNameConfirmationRequiredException) ex).getDetails();
                    assertThat(details.fingerprint()).isEqualTo("new-fp");
                    assertThat(details.visibleCandidates()).hasSize(2);
                });
    }

    @Test
    @DisplayName("AC-08: confirmDuplicate=true だが fingerprint 未指定なら 409")
    void ac08_confirmedWithoutFingerprintThrows409() throws SQLException {
        stubLockAlwaysAcquired();
        when(fingerprintService.issue(any(), any(), any(), any())).thenReturn("dummy-fp");

        assertThatThrownBy(() -> guardService.checkForCreateAndRun(
                DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 1L,
                true, null,
                () -> List.of(new DuplicateNameCandidate("10", true, "サンプル組織")),
                () -> "created"))
                .isInstanceOf(DuplicateNameConfirmationRequiredException.class);
    }

    @Test
    @DisplayName("AC-09: 候補供給コールバックは呼び出しごとに consult される")
    void ac09_candidateSupplierIsConsultedOnEveryCall() throws SQLException {
        stubLockAlwaysAcquired();
        AtomicInteger callCount = new AtomicInteger();

        guardService.checkForCreateAndRun(
                DuplicateNameScopeKind.TEAM, "サンプルチーム", 1L,
                false, null,
                () -> {
                    callCount.incrementAndGet();
                    return List.of();
                },
                () -> "created");

        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("P1-2: GET_LOCK と RELEASE_LOCK が同一の専用接続・同一キーで対になって呼ばれる")
    void p1_2_lockIsAcquiredAndReleasedOnSameDedicatedConnection() throws SQLException {
        stubLockAlwaysAcquired();
        org.mockito.ArgumentCaptor<String> getLockKeyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> releaseLockKeyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);

        guardService.checkForCreateAndRun(
                DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 1L,
                false, null, List::of, () -> "created");

        // GET_LOCK/RELEASE_LOCK ともに同一の専用接続（dataSource.getConnection() が1回だけ
        // 返した connection）上で発行される。
        verify(dataSource, times(1)).getConnection();
        verify(getLockStatement).setString(eq(1), getLockKeyCaptor.capture());
        verify(releaseLockStatement).setString(eq(1), releaseLockKeyCaptor.capture());
        assertThat(getLockKeyCaptor.getValue()).isEqualTo(releaseLockKeyCaptor.getValue());
        // GET_LOCK のキー長上限（64バイト）を超えないことを確認する。
        assertThat(getLockKeyCaptor.getValue().length()).isLessThanOrEqualTo(64);
        verify(connection).close();
    }

    @Test
    @DisplayName("P1-2: GET_LOCK がタイムアウトしたら DUPNAME_002（409）を投げ、候補問い合わせも"
            + "createAction も実行しない。RELEASE_LOCK は呼ばないが専用接続は close する")
    void p1_2_lockTimeoutThrowsDupname002AndSkipsCandidateSupplierAndCreateAction() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(eq("SELECT GET_LOCK(?, ?)"))).thenReturn(getLockStatement);
        when(getLockStatement.executeQuery()).thenReturn(getLockResultSet);
        when(getLockResultSet.next()).thenReturn(true);
        when(getLockResultSet.getInt(1)).thenReturn(0);
        when(getLockResultSet.wasNull()).thenReturn(false);
        AtomicInteger candidateSupplierCallCount = new AtomicInteger();
        AtomicInteger createActionCallCount = new AtomicInteger();

        assertThatThrownBy(() -> guardService.checkForCreateAndRun(
                DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 1L,
                false, null,
                () -> {
                    candidateSupplierCallCount.incrementAndGet();
                    return List.of();
                },
                () -> {
                    createActionCallCount.incrementAndGet();
                    return "created";
                }))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                        .isEqualTo("DUPNAME_002"));
        assertThat(candidateSupplierCallCount.get()).isZero();
        assertThat(createActionCallCount.get()).isZero();
        // ロック取得に失敗しているため RELEASE_LOCK は呼ばない。
        verify(connection, never()).prepareStatement(eq("SELECT RELEASE_LOCK(?)"));
        // が、専用接続自体は必ず close する（P1-2: 残留防止）。
        verify(connection).close();
    }

    @Test
    @DisplayName("確認要求例外発生時もロックは即座に解放・専用接続は close される")
    void lockIsReleasedAndConnectionClosedWhenConfirmationRequiredExceptionThrown() throws SQLException {
        stubLockAlwaysAcquired();
        when(fingerprintService.issue(any(), any(), any(), any())).thenReturn("dummy-fp");

        assertThatThrownBy(() -> guardService.checkForCreateAndRun(
                DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 1L,
                false, null,
                () -> List.of(new DuplicateNameCandidate("10", true, "サンプル組織")),
                () -> "created"))
                .isInstanceOf(DuplicateNameConfirmationRequiredException.class);

        verify(releaseLockStatement).execute();
        verify(connection).close();
    }

    @Test
    @DisplayName("第2巡 P1-1 是正: トランザクションが存在する場合、作成成功後も専用接続は即座には"
            + "close されず、afterCompletion まで解放が遅延される")
    void r2p1_1_lockReleaseIsDeferredUntilTransactionAfterCompletionWhenTransactionActive() throws SQLException {
        stubLockAlwaysAcquired();
        TransactionSynchronizationManager.initSynchronization();
        try {
            AtomicInteger createActionCallCount = new AtomicInteger();

            String result = guardService.checkForCreateAndRun(
                    DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 1L,
                    false, null, List::of,
                    () -> {
                        createActionCallCount.incrementAndGet();
                        return "created";
                    });

            assertThat(result).isEqualTo("created");
            assertThat(createActionCallCount.get()).isEqualTo(1);
            // メソッドは既に返っているが、TX 未 commit のためロックはまだ解放・close されていない。
            verify(connection, never()).close();
            verify(releaseLockStatement, never()).execute();

            // commit 相当（afterCompletion）が起きて初めて解放・close される。
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);
            synchronizations.get(0).afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

            verify(releaseLockStatement).execute();
            verify(connection).close();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("第2巡 P1-1 是正: トランザクションが存在しない場合は createAction 完了直後に即時解放される")
    void r2p1_1_lockIsReleasedImmediatelyWhenNoTransactionActive() throws SQLException {
        stubLockAlwaysAcquired();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();

        guardService.checkForCreateAndRun(
                DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 1L,
                false, null, List::of, () -> "created");

        verify(releaseLockStatement).execute();
        verify(connection).close();
    }

    @Test
    @DisplayName("第2巡 P1-2 是正: RELEASE_LOCK 自体が例外を投げても専用接続の close は必ず呼ばれる")
    void r2p1_2_connectionIsClosedEvenWhenReleaseLockThrows() throws SQLException {
        stubLockAlwaysAcquired();
        when(releaseLockStatement.execute()).thenThrow(new SQLException("RELEASE_LOCK failed"));

        // RELEASE_LOCK の失敗は握りつぶされ、呼び出し元へは伝播しない（専用接続の close さえ
        // 保証されればセッション終了でロックは解放されるため）。
        assertThatCode(() -> guardService.checkForCreateAndRun(
                DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 1L,
                false, null, List::of, () -> "created"))
                .doesNotThrowAnyException();

        verify(connection).close();
    }
}
