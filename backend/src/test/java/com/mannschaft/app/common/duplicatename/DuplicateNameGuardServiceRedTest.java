package com.mannschaft.app.common.duplicatename;

import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CMP-260901-1538 柱③-A「組織・チーム名称の重複許可」受け入れ条件テスト（試練で red として設置し、
 * 出陣で {@link DuplicateNameGuardServiceImpl} を実装して green 化した）。検分 P1-1/P1-2 是正
 * （PRIVATE 候補の id 非開示・アドバイザリロックによる TOCTOU 対策）を反映した第2版。
 *
 * <p>{@link DuplicateNameGuardService} の作成前チェック中核ロジックの最終挙動を直接検査する。
 * {@code jdbcTemplate} は {@code GET_LOCK}/{@code RELEASE_LOCK} をモックし、既定では常に
 * ロック取得成功（{@code 1}）を返す。</p>
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
 *   <li>AC-07 confirmDuplicate=true でも fingerprint が確認時の候補集合と不一致（＝確認後に
 *       新たな同名が出現）なら再度 409 を投げる
 *       → {@link #ac07_fingerprintMismatchAfterConfirmationThrows409Again()}</li>
 *   <li>AC-08 fingerprint 未指定で confirmDuplicate=true を送ると 409（検証不能）
 *       → {@link #ac08_confirmedWithoutFingerprintThrows409()}</li>
 *   <li>AC-09 候補判定は候補供給コールバック（アドバイザリロック保持中）を必ず呼んで得た結果にのみ基づく
 *       → {@link #ac09_candidateSupplierIsConsultedOnEveryCall()}</li>
 *   <li>P1-2 GET_LOCK と RELEASE_LOCK が正しいキーで対になって呼ばれる
 *       → {@link #p1_2_lockIsAcquiredAndReleasedWithSameKey()}</li>
 *   <li>P1-2 GET_LOCK がタイムアウト（0 相当）した場合は DUPNAME_002（409）を投げ、
 *       候補問い合わせも createAction も実行しない
 *       → {@link #p1_2_lockTimeoutThrowsDupname002AndSkipsCandidateSupplierAndCreateAction()}</li>
 *   <li>P1-2 例外発生時もロックは必ず解放される（finally）
 *       → {@link #p1_2_lockIsReleasedEvenWhenConfirmationRequiredExceptionThrown()}</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DuplicateNameGuardServiceRedTest {

    @Mock
    private DuplicateNameFingerprintService fingerprintService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private DuplicateNameGuardServiceImpl guardService;

    private void stubLockAlwaysAcquired() {
        lenient().when(jdbcTemplate.queryForObject(
                        org.mockito.ArgumentMatchers.contains("GET_LOCK"), eq(Integer.class), any(), any()))
                .thenReturn(1);
        lenient().when(jdbcTemplate.queryForObject(
                        org.mockito.ArgumentMatchers.contains("RELEASE_LOCK"), eq(Integer.class), any()))
                .thenReturn(1);
    }

    @Test
    @DisplayName("AC-01: 同名候補が無ければ例外を投げず createAction を実行して結果を返す")
    void ac01_noCandidatesRunsCreateActionAndReturnsResult() {
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
    void ac02_candidatesExistWithoutConfirmationThrows409() {
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
    void ac03_privateCandidateHiddenFromResponseButCounted() {
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
    void ac03_mixedVisibilityAggregatesHiddenCountSeparately() {
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
    void ac06_confirmedWithValidFingerprintRunsCreateAction() {
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
    void ac07_fingerprintMismatchAfterConfirmationThrows409Again() {
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
    void ac08_confirmedWithoutFingerprintThrows409() {
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
    void ac09_candidateSupplierIsConsultedOnEveryCall() {
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
    @DisplayName("P1-2: GET_LOCK と RELEASE_LOCK が正しいキーで対になって呼ばれる")
    void p1_2_lockIsAcquiredAndReleasedWithSameKey() {
        stubLockAlwaysAcquired();
        org.mockito.ArgumentCaptor<String> getLockKeyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> releaseLockKeyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);

        guardService.checkForCreateAndRun(
                DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 1L,
                false, null, List::of, () -> "created");

        verify(jdbcTemplate).queryForObject(
                org.mockito.ArgumentMatchers.contains("GET_LOCK"), eq(Integer.class),
                getLockKeyCaptor.capture(), any());
        verify(jdbcTemplate).queryForObject(
                org.mockito.ArgumentMatchers.contains("RELEASE_LOCK"), eq(Integer.class),
                releaseLockKeyCaptor.capture());
        assertThat(getLockKeyCaptor.getValue()).isEqualTo(releaseLockKeyCaptor.getValue());
        // GET_LOCK のキー長上限（64バイト）を超えないことを確認する。
        assertThat(getLockKeyCaptor.getValue().length()).isLessThanOrEqualTo(64);
    }

    @Test
    @DisplayName("P1-2: GET_LOCK がタイムアウトしたら DUPNAME_002（409）を投げ、"
            + "候補問い合わせも createAction も実行しない")
    void p1_2_lockTimeoutThrowsDupname002AndSkipsCandidateSupplierAndCreateAction() {
        when(jdbcTemplate.queryForObject(
                        org.mockito.ArgumentMatchers.contains("GET_LOCK"), eq(Integer.class), any(), any()))
                .thenReturn(0);
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
        verify(jdbcTemplate, never()).queryForObject(
                org.mockito.ArgumentMatchers.contains("RELEASE_LOCK"), eq(Integer.class), any());
    }

    @Test
    @DisplayName("P1-2: DuplicateNameConfirmationRequiredException 発生時もロックは必ず解放される")
    void p1_2_lockIsReleasedEvenWhenConfirmationRequiredExceptionThrown() {
        stubLockAlwaysAcquired();
        when(fingerprintService.issue(any(), any(), any(), any())).thenReturn("dummy-fp");

        assertThatThrownBy(() -> guardService.checkForCreateAndRun(
                DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 1L,
                false, null,
                () -> List.of(new DuplicateNameCandidate("10", true, "サンプル組織")),
                () -> "created"))
                .isInstanceOf(DuplicateNameConfirmationRequiredException.class);

        verify(jdbcTemplate).queryForObject(
                org.mockito.ArgumentMatchers.contains("RELEASE_LOCK"), eq(Integer.class), any());
    }
}
