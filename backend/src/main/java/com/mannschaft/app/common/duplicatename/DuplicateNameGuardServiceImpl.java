package com.mannschaft.app.common.duplicatename;

import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * CMP-260901-1538 柱③-A: {@link DuplicateNameGuardService} の実装。
 *
 * <h2>検分 P1-2/第2巡 是正: ロックの持ち方（設計判断）</h2>
 * <p>組織名・チーム名は一意制約を持たない（同名の併存を許可する設計のため DB 一意制約で
 * 機械的に TOCTOU を防げない）。そのため MySQL の名前付きアドバイザリロック
 * （{@code GET_LOCK}/{@code RELEASE_LOCK}）で「候補再計算 → 作成」区間を
 * <b>同一正規化名の作成者同士だけ</b>直列化する。</p>
 *
 * <p><b>専用接続方式（第2巡是正）</b>: {@code GET_LOCK} は現在のトランザクションが使う
 * JDBC 接続ではなく、{@link DataSource} から直接取得した<b>専用の JDBC 接続</b>上で取得する。
 * 理由は以下の2点（検分 P1 で指摘された順に対応）:</p>
 * <ol>
 *   <li><b>ロック解放は必ずトランザクション完了後にする</b>: {@code @Transactional} の
 *       commit はサービスメソッド終了後（AOP プロキシがメソッド呼び出しを抜けたあと）に起こる。
 *       同じ接続上で {@code finally} 節から {@code RELEASE_LOCK} を呼ぶと、
 *       メソッド内で解放 → メソッド外で commit という順序になり、解放後・commit 前の窓で
 *       別リクエストが（まだコミットされていない）候補集合を見落として直列化が崩れる。
 *       これを断つため、作成が成功しトランザクションが存在する場合は
 *       {@link TransactionSynchronizationManager#registerSynchronization} の
 *       {@code afterCompletion}（commit・rollback のどちらでも必ず呼ばれる）まで
 *       解放を遅延させる。専用接続を使うことで、サービスメソッドの JPA トランザクションの
 *       commit/rollback タイミングと無関係に、任意のタイミングで {@code RELEASE_LOCK} を
 *       発行できる（同一接続上でなければ {@code RELEASE_LOCK} はそのロックの保持者にしか
     *   効かないため、取得したのと同じ専用接続を最後まで保持し続ける必要がある）。</li>
 *   <li><b>{@code RELEASE_LOCK} 失敗時でも接続プールへロックが残留しない</b>:
 *       専用接続は Hikari 等のコネクションプールに返却する接続ではなく、
 *       {@link DataSource#getConnection()} で直接取得したものを最後に必ず
 *       {@link Connection#close()} する運用にする。MySQL の名前付きロックは
 *       <b>セッション（接続）終了で自動的に解放される</b>ため、{@code RELEASE_LOCK}
 *       自体が例外を投げても、専用接続さえ確実に {@code close} すれば DB 側でロックは
 *       解放される（プール返却ではなく物理切断のため、Hikari 側の「実は解放されていない
 *       接続がプールに戻る」問題も原理的に起こらない）。{@code close} は
 *       {@link #releaseAndClose} 内で {@code try}/{@code finally} により二重に保証する
 *       （{@code RELEASE_LOCK} 実行時の例外を握っても必ず {@code close} へ到達する）。</li>
 * </ol>
 *
 * <p>トランザクションが存在しないコンテキスト（本来は起こらない想定だが、フェイルセーフとして
 * 対応する）では、{@code createAction} 完了直後に即座に解放・切断する。</p>
 *
 * <p>候補再計算は {@code candidateSupplier} 経由でロッキングリード（{@code FOR UPDATE}）を
 * 使う契約とする（呼び出し元がロック取得前に他クエリを発行し REPEATABLE READ スナップショットが
 * 先に確立していても、ロッキングリードはスナップショットを無視して最新を読むため安全）。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DuplicateNameGuardServiceImpl implements DuplicateNameGuardService {

    /** アドバイザリロック取得のタイムアウト（秒）。 */
    static final int LOCK_TIMEOUT_SECONDS = 5;

    /** {@link DuplicateNameFingerprintServiceImpl} の TTL と同値（フォールバック用の概算のみに使用）。 */
    private static final long FALLBACK_TTL_SECONDS = 300;

    /** MySQL {@code GET_LOCK} のキー長上限（64バイト）に収めるため、ハッシュを 32 桁（16byte）に切り詰める。 */
    private static final int LOCK_KEY_HASH_LENGTH = 16;

    private final DuplicateNameFingerprintService fingerprintService;
    private final DataSource dataSource;

    @Override
    public <T> T checkForCreateAndRun(DuplicateNameScopeKind scopeKind, String rawName, Long actorUserId,
            boolean confirmDuplicate, String suppliedFingerprint,
            Supplier<List<DuplicateNameCandidate>> candidateSupplier,
            Supplier<T> createAction) {
        String normalizedName = rawName == null ? "" : rawName.trim();
        String lockKey = buildLockKey(scopeKind, normalizedName);

        // 専用接続（現在のトランザクションが使う接続とは別物）を DataSource から直接取得する。
        Connection lockConnection = openDedicatedConnection();
        // finally で release+close するのは「自分がまだ責任を持っている」場合のみ。
        // 作成成功かつトランザクションが存在する場合は afterCompletion へ責任を委譲する
        // （このフラグを false にした後は、finally からは何もしない）。
        boolean ownsRelease = true;
        // GET_LOCK に成功したかどうか。失敗（タイムアウト）時は RELEASE_LOCK を呼ばず、
        // 専用接続の close のみ行う（取得できていないロックを解放しようとしない）。
        boolean lockAcquired = false;
        try {
            acquireLock(lockConnection, lockKey);
            lockAcquired = true;

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
                    // 何も作成しないため、ロックは finally で即座に解放してよい。
                    String newFingerprint =
                            fingerprintService.issue(scopeKind, normalizedName, actorUserId, candidateIds);
                    throw new DuplicateNameConfirmationRequiredException(
                            buildDetails(newFingerprint, candidates));
                }
                // AC-06: 確認済みで候補集合が完全一致（fingerprint 検証成功）なら続行。
            }

            // AC-01: 候補が空なら確認不要で続行。
            T result = createAction.get();

            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                // トランザクションが存在する場合、ロック解放を commit/rollback 完了後まで遅延させる。
                Connection connectionToReleaseLater = lockConnection;
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        releaseAndClose(connectionToReleaseLater, lockKey, true);
                    }
                });
                ownsRelease = false;
            }
            // トランザクションが存在しない（想定外の）場合はここで finally が即座に解放・切断する。

            return result;
        } finally {
            if (ownsRelease) {
                releaseAndClose(lockConnection, lockKey, lockAcquired);
            }
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

    /**
     * DataSource から専用の JDBC 接続を直接取得する（現在のトランザクションの接続とは無関係）。
     * {@code DataSourceUtils.getConnection} ではなく {@link DataSource#getConnection()} を
     * 直接呼ぶことで、Spring のトランザクション同期に参加しない独立した物理接続を得る。
     */
    private Connection openDedicatedConnection() {
        try {
            return dataSource.getConnection();
        } catch (SQLException e) {
            throw new IllegalStateException("同名確認フロー用の専用DB接続の取得に失敗しました", e);
        }
    }

    private void acquireLock(Connection connection, String lockKey) {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            stmt.setString(1, lockKey);
            stmt.setInt(2, LOCK_TIMEOUT_SECONDS);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                int result = rs.getInt(1);
                if (rs.wasNull() || result != 1) {
                    throw new BusinessException(DuplicateNameErrorCode.DUPNAME_002);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("GET_LOCK の実行に失敗しました", e);
        }
    }

    /**
     * 検分 P1-2 是正: {@code RELEASE_LOCK} 自体が例外を投げても、専用接続の {@code close} には
     * 必ず到達する（{@code try}/{@code finally} で二重に保証）。MySQL の名前付きロックは
     * セッション（接続）終了で自動解放されるため、{@code close} さえ保証できれば
     * {@code RELEASE_LOCK} の成否に関わらずロックは残留しない。
     */
    private void releaseAndClose(Connection connection, String lockKey, boolean lockWasAcquired) {
        try {
            if (lockWasAcquired) {
                try (PreparedStatement stmt = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    stmt.setString(1, lockKey);
                    stmt.execute();
                } catch (SQLException e) {
                    log.warn("RELEASE_LOCK に失敗しましたが、専用接続を close するためロックは"
                            + "セッション終了で解放されます: lockKey={}", lockKey, e);
                }
            }
        } finally {
            try {
                connection.close();
            } catch (SQLException e) {
                log.warn("同名確認フロー用の専用DB接続の close に失敗しました: lockKey={}", lockKey, e);
            }
        }
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
