package com.mannschaft.app.notification.confirmable.repository;

import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * F04.9 確認通知受信者リポジトリ。
 */
public interface ConfirmableNotificationRecipientRepository
        extends JpaRepository<ConfirmableNotificationRecipientEntity, Long> {

    /**
     * 確認トークンで受信者を取得する（トークン確認 API 用）。
     *
     * @param confirmToken 確認トークン（UUID）
     * @return 受信者（存在しない場合 empty）
     */
    Optional<ConfirmableNotificationRecipientEntity> findByConfirmToken(String confirmToken);

    /**
     * 通知IDで受信者一覧を取得する（通知詳細・確認率計算用）。
     *
     * @param notificationId 確認通知ID
     * @return 受信者リスト
     */
    List<ConfirmableNotificationRecipientEntity> findByConfirmableNotificationId(Long notificationId);

    /**
     * ユーザーの未確認かつ除外されていない受信者一覧を取得する（保留中一覧 API 用）。
     *
     * @param userId 対象ユーザーID
     * @return 未確認受信者リスト（作成日時降順）
     */
    List<ConfirmableNotificationRecipientEntity> findByUserIdAndIsConfirmedFalseAndExcludedAtIsNull(Long userId);

    /**
     * ユーザーの未確認かつ除外されていない受信者一覧を、親 {@code confirmableNotification} を
     * JOIN FETCH して一括取得する（インボックス表示用・N+1 防止）。
     *
     * <p>{@link #findByUserIdAndIsConfirmedFalseAndExcludedAtIsNull} と同じ絞り込み条件だが、
     * 親エンティティを同一クエリで取得するため {@code FetchType.LAZY} による追加クエリが発生しない。
     * 既存の保留中一覧 API 呼び出しは変更せず、インボックスアダプタからのみ本メソッドを使用する。</p>
     *
     * @param userId 対象ユーザーID
     * @return 未確認受信者リスト（親 confirmableNotification 付き）
     */
    @Query("SELECT r FROM ConfirmableNotificationRecipientEntity r " +
           "JOIN FETCH r.confirmableNotification " +
           "WHERE r.user.id = :userId AND r.isConfirmed = false AND r.excludedAt IS NULL")
    List<ConfirmableNotificationRecipientEntity> findByUserIdAndIsConfirmedFalseAndExcludedAtIsNullWithNotification(
            @Param("userId") Long userId);

    /**
     * ユーザーの未確認かつ除外されていない受信者一覧を、親 {@code confirmableNotification} を
     * JOIN FETCH しつつ<b>境界付きウィンドウ</b>で取得する（F04.11 統合インボックス Phase3 ③ 用）。
     *
     * <p>{@link #findByUserIdAndIsConfirmedFalseAndExcludedAtIsNullWithNotification(Long)} と同じ絞り込み・
     * 同じ N+1 防止（親を同一クエリで取得）だが、{@link Pageable} で取得件数に上限を設けて無制限 fetch を
     * 避ける（設計書 03_business_logic.md §4）。親 created_at 降順で「直近の保留中」を優先して上位を返すため、
     * 集約側の新着優先順序と整合する。{@code JOIN FETCH} 対象は to-one（コレクションでない）ため
     * ページングはメモリではなく SQL の {@code LIMIT} で効く。</p>
     *
     * @param userId   対象ユーザーID
     * @param pageable 取得上限（{@code PageRequest.of(0, window)}）
     * @return 未確認受信者リスト（親付き・最大 window 件・親 created_at 降順）
     */
    @Query("SELECT r FROM ConfirmableNotificationRecipientEntity r " +
           "JOIN FETCH r.confirmableNotification n " +
           "WHERE r.user.id = :userId AND r.isConfirmed = false AND r.excludedAt IS NULL " +
           "ORDER BY n.createdAt DESC, r.id DESC")
    List<ConfirmableNotificationRecipientEntity> findByUserIdAndIsConfirmedFalseAndExcludedAtIsNullWithNotification(
            @Param("userId") Long userId, Pageable pageable);

    /**
     * 通知IDに紐づく未確認かつ除外されていない受信者を取得する（リマインドバッチ用）。
     *
     * <p>通知が ACTIVE 状態のものに絞ることで、キャンセル・期限切れ通知への
     * 不要なリマインド送信を防ぐ。</p>
     *
     * @param notificationId 確認通知ID
     * @return リマインド対象受信者リスト
     */
    @Query("SELECT r FROM ConfirmableNotificationRecipientEntity r " +
           "JOIN r.confirmableNotification n " +
           "WHERE n.status = 'ACTIVE' AND r.isConfirmed = false AND r.excludedAt IS NULL " +
           "AND n.id = :notificationId")
    List<ConfirmableNotificationRecipientEntity> findActiveUnconfirmedByNotificationId(
            @Param("notificationId") Long notificationId);

    /**
     * 通知IDに対する確認済み受信者数を取得する（確認率計算用）。
     *
     * @param notificationId 確認通知ID
     * @return 確認済み受信者数
     */
    long countByConfirmableNotificationIdAndIsConfirmedTrue(Long notificationId);

    /**
     * 通知IDに対する除外されていない受信者数を取得する（確認率の分母計算用）。
     *
     * @param notificationId 確認通知ID
     * @return 除外されていない受信者数
     */
    long countByConfirmableNotificationIdAndExcludedAtIsNull(Long notificationId);

    /**
     * 通知IDに紐づく受信者のユーザーIDと確認トークンのペアを取得する（メール送信用）。
     *
     * <p>メール送信リスナーが AFTER_COMMIT 後の非同期コンテキストで呼び出すため、
     * Lazy ロードを避けてスカラー値のみを射影する。</p>
     *
     * @param notificationId 確認通知ID
     * @return ユーザーID・confirmToken ペアのリスト（[userId, confirmToken] 形式）
     */
    @Query("SELECT r.user.id, r.confirmToken FROM ConfirmableNotificationRecipientEntity r " +
           "WHERE r.confirmableNotification.id = :notificationId AND r.user IS NOT NULL")
    List<Object[]> findUserIdAndConfirmTokenByNotificationId(
            @Param("notificationId") Long notificationId);
}
