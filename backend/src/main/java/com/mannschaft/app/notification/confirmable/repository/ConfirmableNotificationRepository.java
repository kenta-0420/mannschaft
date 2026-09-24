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
     * CMP-260920-1040: 期限切れ対象の ID だけを抽出する（軍議第8版確定稿 §11.1 手順1）。
     *
     * <p>エンティティは読み込まない。期限切れバッチが ID 1件ごとに独立したトランザクションで
     * {@link #findByIdForUpdate} を呼んで再判定するための入力。</p>
     *
     * @param now 現在日時
     * @return 期限切れ対象の確認通知 ID 一覧
     */
    @Query("SELECT n.id FROM ConfirmableNotificationEntity n " +
           "WHERE n.status = 'ACTIVE' AND n.deadlineAt IS NOT NULL AND n.deadlineAt < :now")
    List<Long> findExpiredIds(@Param("now") LocalDateTime now);
}
