package com.mannschaft.app.notification.repository;

import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 通知リポジトリ。
 */
public interface NotificationRepository extends JpaRepository<NotificationEntity, Long> {

    /**
     * ユーザーの通知一覧をページング取得する（作成日時降順）。
     */
    Page<NotificationEntity> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * ユーザーの未読通知一覧をページング取得する。
     */
    Page<NotificationEntity> findByUserIdAndIsReadFalseOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * IDとユーザーIDで通知を取得する。
     */
    Optional<NotificationEntity> findByIdAndUserId(Long id, Long userId);

    /**
     * ユーザーの未読通知件数を取得する。
     */
    long countByUserIdAndIsReadFalse(Long userId);

    /**
     * ユーザーの全通知件数を取得する。
     */
    long countByUserId(Long userId);

    /**
     * ユーザーの未読通知を全て既読にする。
     */
    @Modifying
    @Query("UPDATE NotificationEntity n SET n.isRead = true, n.readAt = CURRENT_TIMESTAMP WHERE n.userId = :userId AND n.isRead = false")
    int markAllAsReadByUserId(@Param("userId") Long userId);

    /**
     * 全ユーザーの未読通知件数を取得する（管理者統計用）。
     */
    long countByIsReadFalse();

    /**
     * ソースタイプとソースIDで通知件数を取得する。
     */
    long countBySourceTypeAndSourceId(String sourceType, Long sourceId);

    /**
     * スコープタイプとスコープIDで通知一覧をページング取得する（フレンド通知一覧用）。
     *
     * <p><b>scopeType は必ず {@link NotificationScopeType} で受けること。</b>
     * {@code NotificationEntity#scopeType} は {@code @Enumerated(EnumType.STRING)} の enum 属性であり、
     * 派生クエリのバインド型はエンティティ属性側で決まる。{@code String} で宣言すると
     * Hibernate のパラメータ束縛時に
     * {@code InvalidDataAccessApiUsageException: Argument [...] of type [java.lang.String] did not match
     * parameter type [NotificationScopeType]} となり、実行時に必ず 500 になる（列名が同じ VARCHAR でも通らない）。</p>
     */
    Page<NotificationEntity> findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
            NotificationScopeType scopeType, Long scopeId, Pageable pageable);

    /**
     * スコープタイプとスコープIDで未読フィルタ付き通知一覧をページング取得する。
     *
     * <p>scopeType を enum で受ける理由は
     * {@link #findByScopeTypeAndScopeIdOrderByCreatedAtDesc} の javadoc を参照。</p>
     */
    Page<NotificationEntity> findByScopeTypeAndScopeIdAndIsReadOrderByCreatedAtDesc(
            NotificationScopeType scopeType, Long scopeId, Boolean isRead, Pageable pageable);

    /**
     * 組織に紐づく通知一覧を取得する（テナント絞り込み用）。
     *
     * @param organizationId 組織ID
     * @param userId         ユーザーID
     * @return 通知エンティティのリスト
     */
    List<NotificationEntity> findByOrganizationIdAndUserId(Long organizationId, Long userId);

    /**
     * 組織の通知数を返す（シャーディングキー確認用）。
     *
     * @param organizationId 組織ID
     * @return 通知件数
     */
    long countByOrganizationId(Long organizationId);

    /**
     * F15.3: 指定ユーザーの通知を scope_type + scope_id 集合で絞り込んでページング取得する。
     *
     * <p>マイスコープフォルダによるフィルタリングに使用する（設計書 §5.2.4）。
     * scope_id IN (...) と scope_type 完全一致の AND 条件。</p>
     *
     * <p>scopeType を enum で受ける理由は
     * {@link #findByScopeTypeAndScopeIdOrderByCreatedAtDesc} の javadoc を参照。</p>
     *
     * @param userId    ユーザーID
     * @param scopeType スコープタイプ（{@code TEAM} / {@code ORGANIZATION}）
     * @param scopeIds  scope_id 集合（フォルダ内のチーム/組織 ID）
     * @param pageable  ページング
     * @return 通知ページ
     */
    Page<NotificationEntity> findByUserIdAndScopeTypeAndScopeIdInOrderByCreatedAtDesc(
            Long userId, NotificationScopeType scopeType, List<Long> scopeIds, Pageable pageable);

    /**
     * 指定ユーザー向けに、同一の notification_type / source_type / source_id の通知が
     * 指定時刻以降に既に作成されているか判定する（F04.3 期限リマインダー重複送信防止用）。
     *
     * <p>TODO_OVERDUE の毎朝1回配信を実現するため、当日の 00:00 以降に既に
     * 同一 TODO へ同種通知を送っていればスキップする、といった用途を想定する。</p>
     *
     * @param userId           送信先ユーザー ID
     * @param notificationType 通知種別
     * @param sourceType       ソース種別（例: "TODO"）
     * @param sourceId         ソース ID
     * @param since            判定起点時刻（これ以降に作成された通知を対象）
     * @return 既に送信済みであれば true
     */
    boolean existsByUserIdAndNotificationTypeAndSourceTypeAndSourceIdAndCreatedAtGreaterThanEqual(
            Long userId, String notificationType, String sourceType, Long sourceId, LocalDateTime since);

    /**
     * F04.11 Phase3 ②：指定種別を除外したユーザーの通知一覧をページング取得する（作成日時降順）。
     *
     * <p>統合インボックスの NOTIFICATION アダプタが、スヌーズ復帰 push 自身
     * （{@code notification_type = 'INBOX_SNOOZE_REVIVAL'}）を受信箱へ再度流入させない
     * （＝自己増殖を防ぐ）ために使用する。復帰 push はベル/通知一覧には出るが、
     * インボックス受信箱には元のスヌーズ項目が戻るのみとする
     * （設計書 03_business_logic.md §5）。</p>
     *
     * @param userId            ユーザー ID
     * @param excludedType      除外する通知種別（{@code INBOX_SNOOZE_REVIVAL}）
     * @param pageable          ページング
     * @return 除外後の通知ページ
     */
    Page<NotificationEntity> findByUserIdAndNotificationTypeNotOrderByCreatedAtDesc(
            Long userId, String excludedType, Pageable pageable);

    /**
     * F04.11 Phase3 ③：指定種別を除外したユーザーの通知一覧を
     * <b>InboxPriority 相当の優先度順（URGENT→HIGH→NORMAL→LOW）→ 作成日時降順</b>でページング取得する。
     *
     * <p>統合インボックスの NOTIFICATION アダプタが「境界付きウィンドウページング」（Phase3 ③）で
     * 取りこぼしを根絶するために使用する。{@code findByUserIdAndNotificationTypeNotOrderByCreatedAtDesc}
     * は created_at 降順のみのため、「古いが高 priority の通知」が window 外へ脱落しうる（後ページ送りでも
     * page0 で欠落する）。本クエリは取得順を集約サービスのグローバル全順序（priority 第一）に一致させ、
     * 自ソース内のグローバル上位 window 件を漏れなく返す。</p>
     *
     * <p><b>ORDER BY の写像</b>: {@code InboxPriorityNormalizer.mapNotification} と完全一致させる。
     * URGENT=0 / HIGH=1 / LOW=3 / それ以外（NORMAL 等の未知値含む）=2（NORMAL 相当）。
     * これは {@code InboxPriority} の ordinal（URGENT=0, HIGH=1, NORMAL=2, LOW=3）と一致する。
     * 同一 priority 内は created_at 降順（新着優先）。</p>
     *
     * <p>既存の {@code findByUserIdAndNotificationTypeNotOrderByCreatedAtDesc} は他用途（保留中一覧等）が
     * 使用しうるため温存し、本メソッドを新設する（CLAUDE.md 根治原則・既存非破壊）。</p>
     *
     * @param userId       ユーザー ID
     * @param excludedType 除外する通知種別（{@code INBOX_SNOOZE_REVIVAL}）
     * @param pageable     ページング
     * @return 優先度第一順（priority 降順 → created_at 降順）の通知ページ
     */
    @Query("""
            SELECT n FROM NotificationEntity n
            WHERE n.userId = :userId AND n.notificationType <> :excludedType
            ORDER BY
              CASE n.priority
                WHEN com.mannschaft.app.notification.NotificationPriority.URGENT THEN 0
                WHEN com.mannschaft.app.notification.NotificationPriority.HIGH THEN 1
                WHEN com.mannschaft.app.notification.NotificationPriority.LOW THEN 3
                ELSE 2
              END ASC,
              n.createdAt DESC
            """)
    Page<NotificationEntity> findInboxByUserIdOrderByPriorityThenCreatedAtDesc(
            @Param("userId") Long userId, @Param("excludedType") String excludedType, Pageable pageable);

    /**
     * 指定ユーザーの通知本体を全件削除する（クロスドメインFK撤廃キャンペーン 第二陣E）。
     *
     * <p>{@code NotificationAnonymizationEventListener#handleUserAnonymized} が退会受付直後
     * （{@code UserAnonymizedEvent} 即時匿名化）に呼び出し、users 本体削除より前に
     * 通知本体（title / body ＝宛先ユーザー向けの個人の内容＝PII）を先行削除する安全弁メソッド。
     * これにより V100.001 で撤廃する {@code fk_notifications_user}（ON DELETE CASCADE）が冗長になる。</p>
     *
     * <p>{@code NotificationEntity} は {@code @SQLRestriction} を持たず（論理削除カラム deleted_at なし）、
     * 派生 delete クエリでも消し残しは発生しないため通常の派生 delete を用いる。</p>
     *
     * @param userId 退会ユーザーID
     * @return 削除された行数
     */
    int deleteByUserId(Long userId);
}
