package com.mannschaft.app.common.duplicatename;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * CMP-260901-1538 柱③-A「組織・チーム名称の重複許可」受け入れ条件テスト（試練で red として設置し、
 * 出陣で {@link DuplicateNameGuardServiceImpl} を実装して green 化した）。
 *
 * <p>{@link DuplicateNameGuardService} の作成前チェック中核ロジックの最終挙動を直接検査する。</p>
 *
 * <h2>AC ↔ テスト対応</h2>
 * <ul>
 *   <li>AC-01 同名候補が存在しない場合は例外を投げず作成続行を許可する
 *       → {@link #ac01_noCandidatesProceedsWithoutException()}</li>
 *   <li>AC-02 同名候補が存在し confirmDuplicate=false の場合は 409（候補一覧＋fingerprint）を投げる
 *       → {@link #ac02_candidatesExistWithoutConfirmationThrows409()}</li>
 *   <li>AC-03 PRIVATE スコープの候補は名称を開示せず「存在のみ」を示す
 *       → {@link #ac03_privateCandidateHidesNameButIsCounted()}</li>
 *   <li>AC-06 confirmDuplicate=true かつ fingerprint 一致なら作成を続行できる
 *       → {@link #ac06_confirmedWithValidFingerprintProceeds()}</li>
 *   <li>AC-07 confirmDuplicate=true でも fingerprint が確認時の候補集合と不一致（＝確認後に
 *       新たな同名が出現）なら再度 409 を投げる
 *       → {@link #ac07_fingerprintMismatchAfterConfirmationThrows409Again()}</li>
 *   <li>AC-08 fingerprint 未指定で confirmDuplicate=true を送ると 409（検証不能）
 *       → {@link #ac08_confirmedWithoutFingerprintThrows409()}</li>
 *   <li>AC-09 候補判定は候補供給コールバック（TX内実行）を必ず呼んで得た結果にのみ基づく
 *       → {@link #ac09_candidateSupplierIsConsultedOnEveryCall()}</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DuplicateNameGuardServiceRedTest {

    @Mock
    private DuplicateNameFingerprintService fingerprintService;

    @InjectMocks
    private DuplicateNameGuardServiceImpl guardService;

    @Test
    @DisplayName("AC-01: 同名候補が無ければ例外を投げず作成続行を許可する")
    void ac01_noCandidatesProceedsWithoutException() {
        assertThatCode(() -> guardService.checkForCreate(
                DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 1L,
                false, null, List::of))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC-02: 未確認で同名候補があれば 409（候補一覧＋fingerprint）を投げる")
    void ac02_candidatesExistWithoutConfirmationThrows409() {
        when(fingerprintService.issue(any(), any(), any(), any())).thenReturn("dummy-fp");

        assertThatThrownBy(() -> guardService.checkForCreate(
                DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 1L,
                false, null,
                () -> List.of(new DuplicateNameCandidate("10", true, "サンプル組織"))))
                .isInstanceOf(DuplicateNameConfirmationRequiredException.class)
                .satisfies(ex -> {
                    DuplicateNameConfirmationDetails details =
                            ((DuplicateNameConfirmationRequiredException) ex).getDetails();
                    assertThat(details.fingerprint()).isEqualTo("dummy-fp");
                    assertThat(details.candidates()).hasSize(1);
                    assertThat(details.candidates().get(0).nameVisible()).isTrue();
                    assertThat(details.candidates().get(0).name()).isEqualTo("サンプル組織");
                });
    }

    @Test
    @DisplayName("AC-03: PRIVATE 候補は名称を開示せず「存在のみ」を示す")
    void ac03_privateCandidateHidesNameButIsCounted() {
        when(fingerprintService.issue(any(), any(), any(), any())).thenReturn("dummy-fp");

        assertThatThrownBy(() -> guardService.checkForCreate(
                DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 1L,
                false, null,
                () -> List.of(new DuplicateNameCandidate("20", false, null))))
                .isInstanceOf(DuplicateNameConfirmationRequiredException.class)
                .satisfies(ex -> {
                    DuplicateNameConfirmationDetails details =
                            ((DuplicateNameConfirmationRequiredException) ex).getDetails();
                    assertThat(details.candidates()).hasSize(1);
                    assertThat(details.candidates().get(0).nameVisible()).isFalse();
                    assertThat(details.candidates().get(0).name()).isNull();
                });
    }

    @Test
    @DisplayName("AC-06: confirmDuplicate=true かつ fingerprint 一致なら作成続行を許可する")
    void ac06_confirmedWithValidFingerprintProceeds() {
        when(fingerprintService.verify(any(), any(), any(), any(), any())).thenReturn(true);

        assertThatCode(() -> guardService.checkForCreate(
                DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 1L,
                true, "valid-fp",
                () -> List.of(new DuplicateNameCandidate("10", true, "サンプル組織"))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC-07: 確認後に候補集合が変化（fingerprint不一致）していれば再度 409")
    void ac07_fingerprintMismatchAfterConfirmationThrows409Again() {
        when(fingerprintService.verify(any(), any(), any(), any(), any())).thenReturn(false);
        when(fingerprintService.issue(any(), any(), any(), any())).thenReturn("new-fp");

        assertThatThrownBy(() -> guardService.checkForCreate(
                DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 1L,
                true, "stale-fp",
                () -> List.of(
                        new DuplicateNameCandidate("10", true, "サンプル組織"),
                        new DuplicateNameCandidate("11", true, "サンプル組織"))))
                .isInstanceOf(DuplicateNameConfirmationRequiredException.class)
                .satisfies(ex -> {
                    DuplicateNameConfirmationDetails details =
                            ((DuplicateNameConfirmationRequiredException) ex).getDetails();
                    assertThat(details.fingerprint()).isEqualTo("new-fp");
                    assertThat(details.candidates()).hasSize(2);
                });
    }

    @Test
    @DisplayName("AC-08: confirmDuplicate=true だが fingerprint 未指定なら 409")
    void ac08_confirmedWithoutFingerprintThrows409() {
        when(fingerprintService.issue(any(), any(), any(), any())).thenReturn("dummy-fp");

        assertThatThrownBy(() -> guardService.checkForCreate(
                DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 1L,
                true, null,
                () -> List.of(new DuplicateNameCandidate("10", true, "サンプル組織"))))
                .isInstanceOf(DuplicateNameConfirmationRequiredException.class);
    }

    @Test
    @DisplayName("AC-09: 候補供給コールバックは呼び出しごとに consult される")
    void ac09_candidateSupplierIsConsultedOnEveryCall() {
        AtomicInteger callCount = new AtomicInteger();

        guardService.checkForCreate(
                DuplicateNameScopeKind.TEAM, "サンプルチーム", 1L,
                false, null,
                () -> {
                    callCount.incrementAndGet();
                    return List.of();
                });

        assertThat(callCount.get()).isEqualTo(1);
    }
}
