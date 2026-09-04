package com.mannschaft.app.common.duplicatename;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260901-1538 柱③-A「組織・チーム名称の重複許可」試練（red）。
 *
 * <p>{@link DuplicateNameFingerprintService} の HMAC fingerprint 発行・検証の最終挙動を直接検査する。
 * 現時点では {@link DuplicateNameFingerprintServiceImpl} が {@link UnsupportedOperationException}
 * を投げる骨格のみのため、本テストは全件 red（未実装で例外伝播により FAIL）となる。出陣（実装）
 * フェーズで HMAC-SHA256 署名・TTL 検証・束縛検証を実装し green 化する。</p>
 *
 * <h2>AC ↔ テスト対応</h2>
 * <ul>
 *   <li>AC-04 fingerprint はスコープ種別・正規化名称・操作者ユーザーID・候補ID集合・TTL に束縛される
 *       → {@link #ac04_issuedFingerprintVerifiesWithExactSameContext()}</li>
 *   <li>AC-04a 操作者ユーザーIDが異なると検証失敗（横流し防止）
 *       → {@link #ac04a_actorMismatchFailsVerification()}</li>
 *   <li>AC-04b 候補ID集合が異なると検証失敗（確認後の新規同名出現をTX内で検知するための土台）
 *       → {@link #ac04b_candidateSetMismatchFailsVerification()}</li>
 *   <li>AC-04c スコープ種別が異なると検証失敗（組織向けfingerprintのチーム作成流用不可）
 *       → {@link #ac04c_scopeKindMismatchFailsVerification()}</li>
 *   <li>AC-04d 正規化名称が異なると検証失敗
 *       → {@link #ac04d_normalizedNameMismatchFailsVerification()}</li>
 * </ul>
 */
class DuplicateNameFingerprintServiceRedTest {

    private final DuplicateNameFingerprintService fingerprintService = new DuplicateNameFingerprintServiceImpl();

    @Test
    @DisplayName("AC-04: 発行直後・同一コンテキストでの検証は成功する（未実装のため red）")
    void ac04_issuedFingerprintVerifiesWithExactSameContext() {
        List<String> candidateIds = List.of("1", "2");

        String fingerprint = fingerprintService.issue(
                DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 100L, candidateIds);
        boolean valid = fingerprintService.verify(
                fingerprint, DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 100L, candidateIds);

        assertThat(fingerprint).isNotBlank();
        assertThat(valid).isTrue();
    }

    @Test
    @DisplayName("AC-04a: 操作者ユーザーIDが異なると検証失敗（未実装のため red）")
    void ac04a_actorMismatchFailsVerification() {
        List<String> candidateIds = List.of("1");

        String fingerprint = fingerprintService.issue(
                DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 100L, candidateIds);
        boolean valid = fingerprintService.verify(
                fingerprint, DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 999L, candidateIds);

        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("AC-04b: 候補ID集合が異なると検証失敗（未実装のため red）")
    void ac04b_candidateSetMismatchFailsVerification() {
        String fingerprint = fingerprintService.issue(
                DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 100L, List.of("1"));
        boolean valid = fingerprintService.verify(
                fingerprint, DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 100L, List.of("1", "2"));

        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("AC-04c: スコープ種別が異なると検証失敗（未実装のため red）")
    void ac04c_scopeKindMismatchFailsVerification() {
        List<String> candidateIds = List.of("1");

        String fingerprint = fingerprintService.issue(
                DuplicateNameScopeKind.ORGANIZATION, "サンプル", 100L, candidateIds);
        boolean valid = fingerprintService.verify(
                fingerprint, DuplicateNameScopeKind.TEAM, "サンプル", 100L, candidateIds);

        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("AC-04d: 正規化名称が異なると検証失敗（未実装のため red）")
    void ac04d_normalizedNameMismatchFailsVerification() {
        List<String> candidateIds = List.of("1");

        String fingerprint = fingerprintService.issue(
                DuplicateNameScopeKind.ORGANIZATION, "サンプルA", 100L, candidateIds);
        boolean valid = fingerprintService.verify(
                fingerprint, DuplicateNameScopeKind.ORGANIZATION, "サンプルB", 100L, candidateIds);

        assertThat(valid).isFalse();
    }
}
