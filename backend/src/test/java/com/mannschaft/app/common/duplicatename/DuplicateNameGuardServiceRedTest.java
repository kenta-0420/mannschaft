package com.mannschaft.app.common.duplicatename;

import com.mannschaft.app.common.BusinessException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CMP-260901-1538 柱③-A「組織・チーム名称の重複許可」受け入れ条件テスト（試練で red として設置し、
 * 出陣で {@link DuplicateNameGuardServiceImpl} を実装して green 化した）。検分第3巡是正
 * （GET_LOCK 方式を廃止し、{@code duplicate_name_locks} テーブルの行ロック方式へ転換）を
 * 反映した第4版。
 *
 * <p>{@link DuplicateNameGuardService} の作成前チェック中核ロジックの最終挙動を直接検査する。
 * {@code entityManager.createNativeQuery(...)} が返す {@link Query} をモックし、
 * {@code INSERT ... ON DUPLICATE KEY UPDATE} による行ロック取得を検証する。
 * 明示的な解放処理が存在しないこと（InnoDB が commit/rollback で自動解放する設計）は
 * {@link DuplicateNameConcurrentCreationRedIT}（実DB）で検証する。</p>
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
 *   <li>AC-09 候補判定は候補供給コールバック（行ロック保持中）を必ず呼んで得た結果にのみ基づく
 *       → {@link #ac09_candidateSupplierIsConsultedOnEveryCall()}</li>
 *   <li>検分第3巡: 行ロック取得は {@code INSERT ... ON DUPLICATE KEY UPDATE} で行われる
 *       → {@link #r3_rowLockIsAcquiredViaInsertOnDuplicateKeyUpdate()}</li>
 *   <li>検分第3巡: ロック待ちタイムアウトは DUPNAME_002（409）へ写像され、候補問い合わせも
 *       createAction も実行しない → {@link #r3_lockWaitTimeoutMapsToDupname002()}</li>
 *   <li>検分第3巡: デッドロック検出も DUPNAME_002（409）へ写像される
 *       → {@link #r3_deadlockDetectedMapsToDupname002()}</li>
 *   <li>検分第3巡: ロック取得と無関係な RuntimeException はそのまま伝播する（握りつぶさない）
 *       → {@link #r3_unrelatedRuntimeExceptionIsNotSwallowed()}</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DuplicateNameGuardServiceRedTest {

    @Mock
    private DuplicateNameFingerprintService fingerprintService;

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query nativeQuery;

    @InjectMocks
    private DuplicateNameGuardServiceImpl guardService;

    /**
     * {@code entityManager} は {@code @PersistenceContext} によるフィールド注入用のため
     * final にしていない。Mockito の {@code @InjectMocks} はコンストラクタで充足できる
     * フィールド（{@code fingerprintService}）がある場合、それ以外のフィールドへは
     * 自動でフィールド注入しないため、手動で流し込む。
     */
    @BeforeEach
    void injectEntityManager() {
        ReflectionTestUtils.setField(guardService, "entityManager", entityManager);
    }

    /** 行ロック取得（INSERT ... ON DUPLICATE KEY UPDATE）が常に成功する既定スタブ。 */
    private void stubRowLockAlwaysAcquired() {
        lenient().when(entityManager.createNativeQuery(any(String.class))).thenReturn(nativeQuery);
        lenient().when(nativeQuery.setParameter(anyInt(), any())).thenReturn(nativeQuery);
        lenient().when(nativeQuery.executeUpdate()).thenReturn(1);
    }

    @Test
    @DisplayName("AC-01: 同名候補が無ければ例外を投げず createAction を実行して結果を返す")
    void ac01_noCandidatesRunsCreateActionAndReturnsResult() {
        stubRowLockAlwaysAcquired();
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
    void ac02_candidatesExistWithoutConfirmationThrows409() {
        stubRowLockAlwaysAcquired();
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
    void ac03_privateCandidateHiddenFromResponseButCounted() {
        stubRowLockAlwaysAcquired();
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
    void ac03_mixedVisibilityAggregatesHiddenCountSeparately() {
        stubRowLockAlwaysAcquired();
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
    void ac06_confirmedWithValidFingerprintRunsCreateAction() {
        stubRowLockAlwaysAcquired();
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
    void ac07_fingerprintMismatchAfterConfirmationThrows409Again() {
        stubRowLockAlwaysAcquired();
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
    void ac08_confirmedWithoutFingerprintThrows409() {
        stubRowLockAlwaysAcquired();
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
    void ac09_candidateSupplierIsConsultedOnEveryCall() {
        stubRowLockAlwaysAcquired();
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
    @DisplayName("検分第3巡: 行ロック取得は INSERT ... ON DUPLICATE KEY UPDATE で行われる")
    void r3_rowLockIsAcquiredViaInsertOnDuplicateKeyUpdate() {
        stubRowLockAlwaysAcquired();
        org.mockito.ArgumentCaptor<String> sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);

        guardService.checkForCreateAndRun(
                DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 1L,
                false, null, List::of, () -> "created");

        verify(entityManager).createNativeQuery(sqlCaptor.capture());
        String sql = sqlCaptor.getValue();
        assertThat(sql).containsIgnoringCase("INSERT INTO duplicate_name_locks");
        assertThat(sql).containsIgnoringCase("ON DUPLICATE KEY UPDATE");
        verify(nativeQuery).executeUpdate();
    }

    @Test
    @DisplayName("検分第3巡: ロック待ちタイムアウトは DUPNAME_002（409）へ写像され、"
            + "候補問い合わせも createAction も実行しない")
    void r3_lockWaitTimeoutMapsToDupname002() {
        when(entityManager.createNativeQuery(any(String.class))).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyInt(), any())).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate()).thenThrow(new LockTimeoutException("Lock wait timeout exceeded"));
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
    }

    @Test
    @DisplayName("検分第3巡: デッドロック検出も DUPNAME_002（409）へ写像される")
    void r3_deadlockDetectedMapsToDupname002() {
        when(entityManager.createNativeQuery(any(String.class))).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyInt(), any())).thenReturn(nativeQuery);
        when(nativeQuery.executeUpdate())
                .thenThrow(new RuntimeException("could not execute statement; SQL [n/a]; "
                        + "Deadlock found when trying to get lock; try restarting transaction"));

        assertThatThrownBy(() -> guardService.checkForCreateAndRun(
                DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 1L,
                false, null, List::of, () -> "created"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                        .isEqualTo("DUPNAME_002"));
    }

    @Test
    @DisplayName("検分第3巡: ロック取得と無関係な RuntimeException はそのまま伝播する（握りつぶさない）")
    void r3_unrelatedRuntimeExceptionIsNotSwallowed() {
        when(entityManager.createNativeQuery(any(String.class))).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyInt(), any())).thenReturn(nativeQuery);
        IllegalStateException unrelated = new IllegalStateException("接続不可などの想定外エラー");
        when(nativeQuery.executeUpdate()).thenThrow(unrelated);

        assertThatThrownBy(() -> guardService.checkForCreateAndRun(
                DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 1L,
                false, null, List::of, () -> "created"))
                .isSameAs(unrelated);
    }
}
