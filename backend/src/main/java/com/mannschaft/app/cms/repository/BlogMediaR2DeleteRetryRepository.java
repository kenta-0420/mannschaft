package com.mannschaft.app.cms.repository;

import com.mannschaft.app.cms.entity.BlogMediaR2DeleteRetryEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ブログメディア R2 削除リトライ台帳リポジトリ（Issue #2601 別任務）。
 */
public interface BlogMediaR2DeleteRetryRepository extends JpaRepository<BlogMediaR2DeleteRetryEntity, UUID> {

    /** {@code object_key} のハッシュで既存登録の有無を判定する（二重登録防止）。 */
    Optional<BlogMediaR2DeleteRetryEntity> findByObjectKeyHash(String objectKeyHash);

    /**
     * 再試行対象（{@code status=PENDING} かつ {@code next_attempt_at <= :now}）を
     * <b>キーセットページング</b>（{@code id > cursor}）で {@code id} 昇順に取得する
     * （リトライバッチ用）。
     *
     * <p>ループ本体が処理済みの行の {@code status} / {@code next_attempt_at} を書き換えるため、
     * 母集合が走査中に縮む。OFFSET ページング（{@code PageRequest.of(page, size)} でページ番号を
     * 進める方式）にすると、縮んだ分だけ後続の行が OFFSET の網から漏れて読み飛ばされる。
     * 逆にページ 0 固定のドレイン方式にすると、バックオフで {@code next_attempt_at} が未来に
     * 進んだまま残る行（このクエリの絞り込みで除外されるはずの行）がいつまでも取得され続け
     * 無限ループになる。カーソルを直前チャンクの最終 {@code id} まで前進させるキーセット方式のみが、
     * 縮む母集合でも取りこぼしなく・無限ループにもならず全件を走査できる。</p>
     *
     * @param now      現在日時
     * @param cursor   直前チャンクの最終 ID（初回は {@code null}）
     * @param pageable ページング情報（サイズのみ使用。ソートは本クエリで固定）
     * @return {@code id} 昇順のリトライ対象一覧（該当なしは空リスト）
     */
    @Query("""
            SELECT r FROM BlogMediaR2DeleteRetryEntity r
            WHERE r.status = com.mannschaft.app.cms.entity.BlogMediaR2DeleteRetryStatus.PENDING
              AND r.nextAttemptAt <= :now
              AND (:cursor IS NULL OR r.id > :cursor)
            ORDER BY r.id ASC
            """)
    List<BlogMediaR2DeleteRetryEntity> findPendingDueAfterId(
            @Param("now") LocalDateTime now,
            @Param("cursor") UUID cursor,
            Pageable pageable);
}
