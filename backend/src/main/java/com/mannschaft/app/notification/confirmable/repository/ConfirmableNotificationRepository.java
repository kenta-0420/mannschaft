package com.mannschaft.app.notification.confirmable.repository;

import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * F04.9 確認通知リポジトリ。
 */
public interface ConfirmableNotificationRepository
        extends JpaRepository<ConfirmableNotificationEntity, Long> {

    /**
     * スコープ配下の確認通知を作成日時降順で取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return 確認通知リスト（降順）
     */
    List<ConfirmableNotificationEntity> findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
            ScopeType scopeType, Long scopeId);

    /**
     * ステータスで確認通知を取得する（バッチジョブ用）。
     *
     * @param status ステータス
     * @return 確認通知リスト
     */
    List<ConfirmableNotificationEntity> findByStatus(ConfirmableNotificationStatus status);

    /**
     * 期限切れとなった ACTIVE 通知を取得する（期限切れバッチジョブ用）。
     *
     * <p>deadline_at が指定日時より前かつ ACTIVE 状態の通知を返す。
     * バッチジョブがこれを取得して {@code expire()} を呼び出す。</p>
     *
     * @param now 現在日時
     * @return 期限切れ対象の確認通知リスト
     */
    @Query("SELECT n FROM ConfirmableNotificationEntity n " +
           "WHERE n.status = 'ACTIVE' AND n.deadlineAt IS NOT NULL AND n.deadlineAt < :now")
    List<ConfirmableNotificationEntity> findExpiredNotifications(@Param("now") LocalDateTime now);

    /**
     * F22.1 市: 発生元（source_type, source_id）に対し指定ステータスの確認通知が存在するか。
     *
     * <p>最終認証通知の重複発火防止に用いる。{@code FULL→OPEN→再FULL} のように札が再度
     * 充足したとき、未確認（{@code ACTIVE}）の {@code MARKET_FINALIZE} 通知が既に存在すれば
     * 再送しない。{@code idx_cn_source(source_type, source_id)} を利用する。</p>
     *
     * @param sourceType 発生元種別（例: {@code MARKET_FINALIZE}）
     * @param sourceId   発生元レコードID（例: {@code recruitment_listings.id}）
     * @param status     ステータス（{@code ACTIVE} = 未確認）
     * @return 存在すれば true
     */
    boolean existsBySourceTypeAndSourceIdAndStatus(
            String sourceType, Long sourceId, ConfirmableNotificationStatus status);

    /**
     * CMP-260920-1040: 親の行を {@code SELECT ... FOR UPDATE} でロックして読む（軍議第8版確定稿 §9.2・§11.1）。
     *
     * <p>親の行を扱うすべてのトランザクション（chunk・finish・confirm・confirmByToken・cancel・expire・
     * リマインド）は、そのトランザクションで最初に親を読む操作を本メソッドにすること
     * （通常の {@code findById} で先に読み込永続化コンテキストへ載せてはならない）。</p>
     *
     * <p>CI是正3（CMP-260920-1040 / AC-68）: 当初 {@code jakarta.persistence.lock.timeout}
     * （ミリ秒）ヒントで待ち時間を絞る設計だったが、Hibernate の MySQL 方言（{@code MySQLDialect}）は
     * このヒントを解釈しない（MySQL の {@code SELECT ... FOR UPDATE} 構文自体に「N秒待つ」という
     * 形が無く、{@code NOWAIT}/{@code SKIP LOCKED} の2値しか表現できないため、0/-2 以外の値は
     * 単に無視され、実際には {@code innodb_lock_wait_timeout} セッション既定値（通常50秒）のまま
     * 待ち続けていた）。この番人違反を機に、{@link com.mannschaft.app.notification.confirmable.service.
     * ConfirmableNotificationExpiryBatchService#expireOneWithLock} 側で {@code SET SESSION
     * innodb_lock_wait_timeout} をトランザクション開始直後に明示発行する方式へ改めた
     * （{@code SET SESSION} は {@code SET GLOBAL} と異なり {@code SUPER} 権限を要らない）。</p>
     *
     * @param id 確認通知 ID
     * @return ロック済みの確認通知（存在しなければ empty）
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT n FROM ConfirmableNotificationEntity n WHERE n.id = :id")
    Optional<ConfirmableNotificationEntity> findByIdForUpdate(@Param("id") Long id);

    /**
     * CMP-260920-1040是正（⚔️足軽19・CI是正4）: 期限切れバッチ専用の {@code FOR UPDATE NOWAIT} 取得。
     *
     * <p><b>前任（{@code findByIdForUpdateWithShortLockWait}）の誤り</b>: 「{@code innodb_lock_wait_timeout}
     * はオプティマイザヒント {@code SET_VAR} の対応変数であり、公式マニュアルにも明記され公式サンプルも
     * 同じ形だ」と報告していたが、これは誤りだった。MySQL 8.0 公式マニュアル「Optimizer Hints」の
     * {@code SET_VAR} がサポートするシステム変数の属性表で、{@code innodb_lock_wait_timeout} は
     * 「SET_VAR Hint Applies: No」であり、ヒントは構文としては受理されるが<b>黙って無視される</b>。
     * そのため期限切れバッチは他トランザクションのロック中の行に対して、セッション既定値（通常50秒）
     * のまま待ち続け、AC-68（ロック中の1件がすぐ失敗し、ほかはこの回で EXPIRED になる）が満たせなかった。</p>
     *
     * <p><b>是正方針</b>: 期限切れバッチは定期的に回るバッチである。ほかのトランザクションがロック中の
     * 通知は、この回は<b>待たずに</b>諦めて次の回に回せばよい（次の回で必ず再評価される。これは握りつぶし
     * ではなく正当な制御である）。{@code jakarta.persistence.lock.timeout} を {@code 0} にする
     * {@code @QueryHint} を JPQL の {@code @Lock(PESSIMISTIC_WRITE)} に付与する。Hibernate の MySQL
     * 方言はこのヒント値 0 を {@code FOR UPDATE NOWAIT} として SQL に出す（ミリ秒指定の「N秒待つ」表現は
     * MySQL の構文に無いため、0（NOWAIT）/-2（SKIP LOCKED）以外の値は無視されるが、0 は明示サポートされる）。
     * ロックが取れなければ即座に {@link jakarta.persistence.PessimisticLockException} 系の例外
     * （Spring Data JPA 変換後は {@link org.springframework.dao.PessimisticLockingFailureException} /
     * {@link org.springframework.dao.CannotAcquireLockException}）を投げる。呼び出し元
     * （{@code ConfirmableNotificationExpiryBatchService}）は1件ごとの独立トランザクションでこれを
     * catch し、「この回はスキップした」として件数・ログに記録し、ほかの ID の処理は続ける。</p>
     *
     * @param id 確認通知 ID
     * @return ロック済みの確認通知（存在しなければ empty）
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.QueryHints(@jakarta.persistence.QueryHint(
            name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("SELECT n FROM ConfirmableNotificationEntity n WHERE n.id = :id")
    Optional<ConfirmableNotificationEntity> findByIdForUpdateNoWait(@Param("id") Long id);

    /**
     * CMP-260920-1040: 期限切れ対象の ID だけを抽出する（軍議第8版確定稿 §11.1 手順1）。
     *
     * <p>エンティティは読み込まない。期限切れバッチが ID 1件ごとに独立したトランザクションで
     * {@link #findByIdForUpdateNoWait} を呼んで再判定するための入力。</p>
     *
     * @param now 現在日時
     * @return 期限切れ対象の確認通知 ID 一覧
     */
    @Query("SELECT n.id FROM ConfirmableNotificationEntity n " +
           "WHERE n.status = 'ACTIVE' AND n.deadlineAt IS NOT NULL AND n.deadlineAt < :now")
    List<Long> findExpiredIds(@Param("now") LocalDateTime now);
}
