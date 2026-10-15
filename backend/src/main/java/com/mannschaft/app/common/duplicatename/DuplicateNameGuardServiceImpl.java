package com.mannschaft.app.common.duplicatename;

import com.mannschaft.app.common.BusinessException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PessimisticLockException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * CMP-260901-1538 柱③-A: {@link DuplicateNameGuardService} の実装。
 *
 * <h2>検分第3巡是正（設計判断）: ロック専用テーブルの行ロック方式</h2>
 * <p>組織名・チーム名は一意制約を持たない（同名の併存を許可する設計のため、通常の
 * {@code UNIQUE} 制約では TOCTOU を機械的に防げない）。第1〜2巡では MySQL の名前付き
 * アドバイザリロック（{@code GET_LOCK}/{@code RELEASE_LOCK}）で直列化を試みたが、
 * 「解放のタイミング」と「専用接続の管理」に構造的な問題が消えなかった
 * （rollback 経路での早期解放、Hikari 経由では {@code close()} が物理切断ではなく
 * プール返却になる、専用接続を保持し続けることによる接続プール枯渇）。</p>
 *
 * <p>そのため <b>{@code duplicate_name_locks} テーブルの行ロック</b>方式へ転換した:</p>
 * <ol>
 *   <li>呼び出し元と<b>同一トランザクション</b>内で
 *       {@code INSERT INTO duplicate_name_locks ... ON DUPLICATE KEY UPDATE scope_kind = scope_kind}
 *       を実行する（既存行でも X ロック＝排他ロックを取得できる。行が無ければ挿入、
 *       あれば無害な自己代入 UPDATE でロックだけ取る）。</li>
 *   <li>続けて {@code candidateSupplier}（{@code FOR UPDATE} ロッキングリード）で
 *       最新のコミット済み候補集合を読む。</li>
 *   <li>{@code createAction}（実際の作成処理）も同一トランザクション内で実行する。</li>
 *   <li><b>明示的な解放処理は一切書かない。</b> InnoDB は commit・rollback のどちらでも
 *       そのトランザクションが保持する行ロックを自動的に解放するため、
 *       解放漏れが原理的に起こらない（専用接続・{@code afterCompletion}・
 *       {@code RELEASE_LOCK} がすべて不要になる）。</li>
 * </ol>
 *
 * <p>ロック待ちは MySQL の {@code innodb_lock_wait_timeout} に委ねる。タイムアウト時は
 * {@link LockTimeoutException}/{@link PessimisticLockException}（または
 * 同義の "Lock wait timeout exceeded" を含む例外）として現れるため、
 * {@link DuplicateNameErrorCode#DUPNAME_002}（409）へ写像する。</p>
 *
 * <p>{@code duplicate_name_locks} は実データを持たない恒久的なロック専用テーブルであり、
 * ドメイン間 FK は張らない（{@code docs/architecture/domain_db_design_principles.md} 原則1）。
 * 主キーは自然キー（複合PK: {@code scope_kind, name_key}）のままとし {@code UuidV7Entity} は
 * 適用しない（同原則6の例外区分「マスタ例外」に準じる。シャーディング時は全シャードへ
 * 同じ行をコピーする運用が自然であり、原則6の意図＝各ノード独立発番に該当しないため）。</p>
 *
 * <h2>検分第5巡是正: 正規化は DuplicateNameNormalizer#trimSpaces に一本化</h2>
 * <p>Java の {@link String#trim()} は制御文字（タブ・改行等）も除去するが、MySQL の
 * {@code TRIM()} は半角スペースのみを除去する。両者が混在すると、ロックキー生成（Java 側）と
 * 候補検索（DB 側の生成列 {@code name_trimmed}）の正規化基準が食い違い、
 * 例えば {@code "foo\t"}（末尾タブ）が既存 {@code "foo"} と Java 側では同一視されてしまい
 * ロックキーが一致する一方、DB 側の {@code TRIM()} 相当ではタブが除去されず別名として
 * 扱われ候補検索をすり抜ける、という不整合が起こり得る。そのため名称の正規化は
 * {@link DuplicateNameNormalizer#trimSpaces} のみを使い、ロックキー生成・候補検索・
 * fingerprint 計算のすべてで同一基準に統一する。</p>
 */
@Service
@RequiredArgsConstructor
public class DuplicateNameGuardServiceImpl implements DuplicateNameGuardService {

    /** {@link DuplicateNameFingerprintServiceImpl} の TTL と同値（フォールバック用の概算のみに使用）。 */
    private static final long FALLBACK_TTL_SECONDS = 300;

    private final DuplicateNameFingerprintService fingerprintService;

    // @PersistenceContext はフィールド注入用のため final にしない
    // （@RequiredArgsConstructor はコンストラクタ引数化された final フィールドのみを対象とする）。
    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public <T> T checkForCreateAndRun(DuplicateNameScopeKind scopeKind, String rawName, Long actorUserId,
            boolean confirmDuplicate, String suppliedFingerprint,
            Supplier<List<DuplicateNameCandidate>> candidateSupplier,
            Supplier<T> createAction) {
        // 検分第5巡是正: Java の String#trim() ではなく DuplicateNameNormalizer#trimSpaces
        // （MySQL TRIM() と同じ「半角スペースのみ除去」規則）を同名確認フロー唯一の正規化とする。
        String normalizedName = DuplicateNameNormalizer.trimSpaces(rawName);
        String nameKey = buildNameKey(scopeKind, normalizedName);

        // 呼び出し元と同一トランザクション内で行ロックを取得する。解放処理は書かない
        // （InnoDB が commit/rollback で自動的に行ロックを解放するため）。
        acquireRowLock(scopeKind, nameKey);

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

    /**
     * 検分第3巡是正: {@code duplicate_name_locks} テーブルへの
     * {@code INSERT ... ON DUPLICATE KEY UPDATE} で行ロック（X ロック）を取得する。
     * 呼び出し元の {@code EntityManager}（＝呼び出し元のトランザクション）上で実行するため、
     * このロックは呼び出し元のトランザクションが commit/rollback するまで保持される。
     *
     * @throws BusinessException {@code DUPNAME_002}（409） ロック待ちが
     *         {@code innodb_lock_wait_timeout} を超えた場合（同名同士の同時作成が競合）
     */
    private void acquireRowLock(DuplicateNameScopeKind scopeKind, String nameKey) {
        try {
            entityManager.createNativeQuery(
                            "INSERT INTO duplicate_name_locks (scope_kind, name_key) VALUES (?1, ?2) "
                                    + "ON DUPLICATE KEY UPDATE scope_kind = scope_kind")
                    .setParameter(1, scopeKind.name())
                    .setParameter(2, nameKey)
                    .executeUpdate();
        } catch (RuntimeException e) {
            if (isLockTimeout(e)) {
                throw new BusinessException(DuplicateNameErrorCode.DUPNAME_002);
            }
            throw e;
        }
    }

    /**
     * 例外連鎖を辿り、ロック待ちタイムアウト（MySQL の {@code innodb_lock_wait_timeout} 超過、
     * エラー1205「Lock wait timeout exceeded」）またはデッドロック検出かどうかを判定する。
     */
    private boolean isLockTimeout(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null) {
            if (cause instanceof LockTimeoutException || cause instanceof PessimisticLockException) {
                return true;
            }
            String message = cause.getMessage();
            if (message != null
                    && (message.contains("Lock wait timeout exceeded") || message.contains("Deadlock found"))) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * 検分第4巡是正: ロック対象キーは trim 済みの<b>生の名称そのもの</b>を使う
     * （以前は SHA-256 ハッシュを使っていたが、Java 側で MySQL の
     * {@code utf8mb4_0900_ai_ci} 照合を再現できず、"Foo" と "foo" が異なるハッシュ値＝
     * 別ロック行になってしまい、直列化・候補検索の同名判定と食い違っていた）。
     * {@code duplicate_name_locks} テーブル自体が {@code utf8mb4_0900_ai_ci} で作成されて
     * いるため、この文字列をそのまま複合PKへ格納すれば、PK の等価判定
     * （{@code INSERT ... ON DUPLICATE KEY UPDATE} の重複検知）が候補検索
     * （{@code name_trimmed = TRIM(?)}）と完全に同じ照合順序になる。
     */
    private String buildNameKey(DuplicateNameScopeKind scopeKind, String normalizedName) {
        return normalizedName;
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
