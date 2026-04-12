package com.mannschaft.app.notification.confirmable.repository;

import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity;
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
}
