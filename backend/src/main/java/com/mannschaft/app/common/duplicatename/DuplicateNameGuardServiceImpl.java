package com.mannschaft.app.common.duplicatename;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Supplier;

/**
 * CMP-260901-1538 柱③-A: {@link DuplicateNameGuardService} の実装。
 *
 * <p>{@code candidateSupplier} を作成 TX 内で必ず呼び、その結果のみに基づいて判定する
 * （呼び出し元が trim・utf8mb4_0900_ai_ci 比較済みの結果を渡す契約。作成 TX 内で呼ぶことで
 * 「確認時点」と「作成時点」の候補集合の差分を機械的に検知できる）。</p>
 */
@Service
@RequiredArgsConstructor
public class DuplicateNameGuardServiceImpl implements DuplicateNameGuardService {

    private final DuplicateNameFingerprintService fingerprintService;

    @Override
    public void checkForCreate(DuplicateNameScopeKind scopeKind, String rawName, Long actorUserId,
            boolean confirmDuplicate, String suppliedFingerprint,
            Supplier<List<DuplicateNameCandidate>> candidateSupplier) {
        String normalizedName = rawName == null ? "" : rawName.trim();
        List<DuplicateNameCandidate> candidates = candidateSupplier.get();

        if (candidates.isEmpty()) {
            // AC-01: 同名候補が無ければ確認不要で作成続行を許可する。
            return;
        }

        List<String> candidateIds = candidates.stream().map(DuplicateNameCandidate::id).toList();

        if (confirmDuplicate && suppliedFingerprint != null && !suppliedFingerprint.isBlank()) {
            boolean valid = fingerprintService.verify(
                    suppliedFingerprint, scopeKind, normalizedName, actorUserId, candidateIds);
            if (valid) {
                // AC-06: 確認済みで、確認時に提示した候補集合と作成 TX 内で再計算した候補集合が
                // 完全一致（fingerprint 検証成功）なら作成続行を許可する。
                return;
            }
            // AC-07: confirmDuplicate=true だが fingerprint が現在の候補集合と不一致
            // （＝確認後に新たな同名が出現した、または集合が変化した）。新規 fingerprint を
            // 発行し直したうえで再度確認要求を投げる。
        }
        // AC-02: 未確認（confirmDuplicate=false）、または AC-08: confirmDuplicate=true だが
        // fingerprint 未指定（検証不能）の場合も、ここに合流して確認要求を投げる。

        String newFingerprint = fingerprintService.issue(scopeKind, normalizedName, actorUserId, candidateIds);
        throw new DuplicateNameConfirmationRequiredException(
                new DuplicateNameConfirmationDetails(newFingerprint, expiresAtOf(newFingerprint), candidates));
    }

    /**
     * 発行済み fingerprint（{@code issuedAt.expiresAt.signature} 形式）から expiresAt を取り出す。
     * {@link DuplicateNameFingerprintService} は expiresAt を単独では返さないため、
     * フォーマットを知る本クラスがここで抽出する。フォーマット不一致（テストダブル等）の場合は
     * TTL（{@link #FALLBACK_TTL_SECONDS}）から概算する（409 応答の付随情報であり、
     * fingerprint 自体の正当性はあくまで {@link DuplicateNameFingerprintService#verify} が担う）。
     */
    private long expiresAtOf(String fingerprint) {
        String[] parts = fingerprint.split("\\.", 3);
        if (parts.length == 3) {
            try {
                return Long.parseLong(parts[1]);
            } catch (NumberFormatException ignored) {
                // フォールスルー
            }
        }
        return java.time.Instant.now().getEpochSecond() + FALLBACK_TTL_SECONDS;
    }

    /** {@link DuplicateNameFingerprintServiceImpl} の TTL と同値（フォールバック用の概算のみに使用）。 */
    private static final long FALLBACK_TTL_SECONDS = 300;
}
