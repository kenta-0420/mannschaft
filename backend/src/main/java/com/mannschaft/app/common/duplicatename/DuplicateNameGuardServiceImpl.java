package com.mannschaft.app.common.duplicatename;

import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * CMP-260901-1538 柱③-A: {@link DuplicateNameGuardService} の実装。
 *
 * <p>検分 P1-2 是正（TOCTOU 対策の設計判断）: 組織名・チーム名は一意制約を持たない
 * （同名の併存を許可する設計のため DB 一意制約で機械的に防げない）。そのため
 * MySQL の名前付きアドバイザリロック（{@code GET_LOCK}/{@code RELEASE_LOCK}）を用い、
 * 「候補再計算 → 作成」区間を <b>同一正規化名の作成者同士だけ</b>直列化する。
 * ロックは {@link #LOCK_TIMEOUT_SECONDS} 秒でタイムアウトし、トランザクション終了・
 * 接続切断で確実に解放される（{@code GET_LOCK} はセッションスコープの MySQL 組込み関数のため、
 * アプリ側のクラッシュ等でも DB 側が自動的に解放する）。{@code candidateSupplier} は
 * ロッキングリード（{@code FOR UPDATE}）で最新のコミット済みデータを読む契約とする
 * （呼び出し元がロック取得前に他クエリを発行し REPEATABLE READ スナップショットが
 * 先に確立していても、ロッキングリードはスナップショットを無視して最新を読むため安全）。
 * {@code createAction}（実際の作成処理）もロック保持中に実行し、判定と作成の間に
 * 別の同名作成者が割り込めないようにする。</p>
 */
@Service
@RequiredArgsConstructor
public class DuplicateNameGuardServiceImpl implements DuplicateNameGuardService {

    /** アドバイザリロック取得のタイムアウト（秒）。 */
    static final int LOCK_TIMEOUT_SECONDS = 5;

    /** {@link DuplicateNameFingerprintServiceImpl} の TTL と同値（フォールバック用の概算のみに使用）。 */
    private static final long FALLBACK_TTL_SECONDS = 300;

    /** MySQL {@code GET_LOCK} のキー長上限（64バイト）に収めるため、ハッシュを 32 桁（16byte）に切り詰める。 */
    private static final int LOCK_KEY_HASH_LENGTH = 16;

    private final DuplicateNameFingerprintService fingerprintService;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public <T> T checkForCreateAndRun(DuplicateNameScopeKind scopeKind, String rawName, Long actorUserId,
            boolean confirmDuplicate, String suppliedFingerprint,
            Supplier<List<DuplicateNameCandidate>> candidateSupplier,
            Supplier<T> createAction) {
        String normalizedName = rawName == null ? "" : rawName.trim();
        String lockKey = buildLockKey(scopeKind, normalizedName);

        acquireLock(lockKey);
        try {
            List<DuplicateNameCandidate> candidates = candidateSupplier.get();

            if (!candidates.isEmpty()) {
                List<String> candidateIds = candidates.stream().map(DuplicateNameCandidate::id).toList();

                boolean proceed = confirmDuplicate
                        && suppliedFingerprint != null
                        && !suppliedFingerprint.isBlank()
                        && fingerprintService.verify(
                                suppliedFingerprint, scopeKind, normalizedName, actorUserId, candidateIds);

                if (!proceed) {
                    // AC-02/AC-07/AC-08: 未確認、fingerprint 不一致（確認後に新規同名が出現）、
                    // fingerprint 未指定のいずれもここに合流し、最新候補集合で新規 fingerprint を発行し直す。
                    String newFingerprint =
                            fingerprintService.issue(scopeKind, normalizedName, actorUserId, candidateIds);
                    throw new DuplicateNameConfirmationRequiredException(
                            buildDetails(newFingerprint, candidates));
                }
                // AC-06: 確認済みで候補集合が完全一致（fingerprint 検証成功）なら続行。
            }

            // AC-01: 候補が空なら確認不要で続行。
            return createAction.get();
        } finally {
            releaseLock(lockKey);
        }
    }

    /**
     * 検分 P1-1 是正: 候補一覧をクライアント応答向けに変換する。PUBLIC（可視）候補のみ
     * {@code visibleCandidates} に id・名称を含め、それ以外は件数のみに畳む
     * （id・slug 等の識別子を一切含めない）。
     */
    private DuplicateNameConfirmationDetails buildDetails(String fingerprint, List<DuplicateNameCandidate> candidates) {
        List<DuplicateNameCandidateView> visible = candidates.stream()
                .filter(DuplicateNameCandidate::nameVisible)
                .map(c -> new DuplicateNameCandidateView(c.id(), c.name()))
                .toList();
        int hiddenCount = candidates.size() - visible.size();
        return new DuplicateNameConfirmationDetails(fingerprint, expiresAtOf(fingerprint), visible, hiddenCount);
    }

    private void acquireLock(String lockKey) {
        Integer result = jdbcTemplate.queryForObject(
                "SELECT GET_LOCK(?, ?)", Integer.class, lockKey, LOCK_TIMEOUT_SECONDS);
        if (result == null || result != 1) {
            throw new BusinessException(DuplicateNameErrorCode.DUPNAME_002);
        }
    }

    private void releaseLock(String lockKey) {
        jdbcTemplate.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, lockKey);
    }

    /**
     * ロックキーを組み立てる。{@code GET_LOCK} のキー長上限（64バイト）を超えないよう、
     * {@code scopeKind + 正規化名} を SHA-256 でハッシュ化し先頭 {@link #LOCK_KEY_HASH_LENGTH}
     * バイト（32 桁の16進文字列）のみを使う。
     */
    private String buildLockKey(DuplicateNameScopeKind scopeKind, String normalizedName) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    (scopeKind.name() + ":" + normalizedName).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < LOCK_KEY_HASH_LENGTH; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return "dupname:" + hex;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 は JDK に必ず存在するはずである", e);
        }
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
        return Instant.now().getEpochSecond() + FALLBACK_TTL_SECONDS;
    }
}
