package com.mannschaft.app.common.duplicatename;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260901-1538 柱③-A「組織・チーム名称の重複許可」受け入れ条件テスト（試練で red として設置し、
 * 出陣で {@link DuplicateNameFingerprintServiceImpl}（HMAC-SHA256）を実装して green 化した）。
 *
 * <p>{@link DuplicateNameFingerprintService} の HMAC fingerprint 発行・検証の最終挙動を直接検査する。</p>
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
 *   <li>P2-4 expiresAt ちょうど（境界）は失効として扱う（{@code >=} now）
 *       → {@link #p2_4_expiresAtExactlyNowIsExpired()}</li>
 *   <li>P2-4 issuedAt が現在時刻より未来なら無効
 *       → {@link #p2_4_issuedAtInFutureIsInvalid()}</li>
 *   <li>P2-4 expiresAt-issuedAt が TTL(300秒) と一致しなければ無効（TTL 延長の改竄防止）
 *       → {@link #p2_4_ttlMismatchIsInvalid()}</li>
 * </ul>
 */
class DuplicateNameFingerprintServiceRedTest {

    private final DuplicateNameFingerprintServiceImpl fingerprintServiceImpl =
            new DuplicateNameFingerprintServiceImpl("", "test-fallback-secret-key-not-for-production-use");

    private final DuplicateNameFingerprintService fingerprintService = fingerprintServiceImpl;

    @Test
    @DisplayName("AC-04: 発行直後・同一コンテキストでの検証は成功する")
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
    @DisplayName("AC-04a: 操作者ユーザーIDが異なると検証失敗")
    void ac04a_actorMismatchFailsVerification() {
        List<String> candidateIds = List.of("1");

        String fingerprint = fingerprintService.issue(
                DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 100L, candidateIds);
        boolean valid = fingerprintService.verify(
                fingerprint, DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 999L, candidateIds);

        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("AC-04b: 候補ID集合が異なると検証失敗")
    void ac04b_candidateSetMismatchFailsVerification() {
        String fingerprint = fingerprintService.issue(
                DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 100L, List.of("1"));
        boolean valid = fingerprintService.verify(
                fingerprint, DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 100L, List.of("1", "2"));

        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("AC-04c: スコープ種別が異なると検証失敗")
    void ac04c_scopeKindMismatchFailsVerification() {
        List<String> candidateIds = List.of("1");

        String fingerprint = fingerprintService.issue(
                DuplicateNameScopeKind.ORGANIZATION, "サンプル", 100L, candidateIds);
        boolean valid = fingerprintService.verify(
                fingerprint, DuplicateNameScopeKind.TEAM, "サンプル", 100L, candidateIds);

        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("AC-04d: 正規化名称が異なると検証失敗")
    void ac04d_normalizedNameMismatchFailsVerification() {
        List<String> candidateIds = List.of("1");

        String fingerprint = fingerprintService.issue(
                DuplicateNameScopeKind.ORGANIZATION, "サンプルA", 100L, candidateIds);
        boolean valid = fingerprintService.verify(
                fingerprint, DuplicateNameScopeKind.ORGANIZATION, "サンプルB", 100L, candidateIds);

        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("P2-4: expiresAt ちょうど（境界）は失効として扱う（>= now）")
    void p2_4_expiresAtExactlyNowIsExpired() throws Exception {
        long now = Instant.now().getEpochSecond();
        long issuedAt = now - 300;
        long expiresAt = now; // ちょうど今 = 境界

        String fingerprint = craftFingerprint(
                DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 100L, List.of("1"), issuedAt, expiresAt);

        boolean valid = fingerprintService.verify(
                fingerprint, DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 100L, List.of("1"));

        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("P2-4: issuedAt が現在時刻より未来なら無効")
    void p2_4_issuedAtInFutureIsInvalid() throws Exception {
        long issuedAt = Instant.now().getEpochSecond() + 100;
        long expiresAt = issuedAt + 300;

        String fingerprint = craftFingerprint(
                DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 100L, List.of("1"), issuedAt, expiresAt);

        boolean valid = fingerprintService.verify(
                fingerprint, DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 100L, List.of("1"));

        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("P2-4: expiresAt-issuedAt が TTL(300秒) と一致しなければ無効（TTL 延長の改竄防止）")
    void p2_4_ttlMismatchIsInvalid() throws Exception {
        long issuedAt = Instant.now().getEpochSecond();
        long expiresAt = issuedAt + 1000; // TTL 300 秒ではなく 1000 秒に延長

        String fingerprint = craftFingerprint(
                DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 100L, List.of("1"), issuedAt, expiresAt);

        boolean valid = fingerprintService.verify(
                fingerprint, DuplicateNameScopeKind.ORGANIZATION, "サンプル組織", 100L, List.of("1"));

        assertThat(valid).isFalse();
    }

    /**
     * {@link DuplicateNameFingerprintServiceImpl#issue} は issuedAt/expiresAt を
     * 常に自前で（正しい TTL で）生成するため、TTL 境界異常系を作るには private の
     * {@code buildPayload}/{@code sign} をリフレクションで直接叩き、任意の issuedAt/expiresAt を
     * 持つ「正しく署名された」fingerprint を組み立てる必要がある（署名を割らずに検証ロジック単体を
     * 直接検査するテスト技法。攻撃者が秘密鍵を得る前提ではない）。
     */
    private String craftFingerprint(DuplicateNameScopeKind scopeKind, String normalizedName, Long actorUserId,
            List<String> candidateIds, long issuedAt, long expiresAt) throws Exception {
        Method buildPayload = DuplicateNameFingerprintServiceImpl.class.getDeclaredMethod(
                "buildPayload", DuplicateNameScopeKind.class, String.class, Long.class, List.class,
                long.class, long.class);
        buildPayload.setAccessible(true);
        String payload = (String) buildPayload.invoke(
                fingerprintServiceImpl, scopeKind, normalizedName, actorUserId, candidateIds, issuedAt, expiresAt);

        Method sign = DuplicateNameFingerprintServiceImpl.class.getDeclaredMethod("sign", String.class);
        sign.setAccessible(true);
        String signature = (String) sign.invoke(fingerprintServiceImpl, payload);

        return issuedAt + "." + expiresAt + "." + signature;
    }
}
