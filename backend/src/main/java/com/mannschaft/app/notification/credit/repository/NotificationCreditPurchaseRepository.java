package com.mannschaft.app.notification.credit.repository;

import com.mannschaft.app.notification.credit.entity.NotificationCreditPurchaseEntity;
import com.mannschaft.app.notification.credit.entity.NotificationCreditPurchaseStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通知クレジット購入履歴リポジトリ。
 */
public interface NotificationCreditPurchaseRepository extends JpaRepository<NotificationCreditPurchaseEntity, Long> {

    /**
     * 組織の購入履歴を支払日昇順（FIFO）で取得する。
     *
     * @param organizationId 組織ID
     * @param status         絞り込むステータス
     * @return 購入履歴リスト
     */
    List<NotificationCreditPurchaseEntity> findByOrganizationIdAndPaymentStatusOrderByPaidAtAsc(
            Long organizationId, NotificationCreditPurchaseStatus status);

    /**
     * 組織の購入履歴を作成日降順で取得する（一覧表示用）。
     *
     * @param organizationId 組織ID
     * @return 購入履歴リスト
     */
    List<NotificationCreditPurchaseEntity> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    /**
     * 有効期限切れ対象（失効前）を取得する（有効期限切れバッチ用）。
     *
     * @param now        現在日時
     * @param status     ステータス（PAIDのみ対象）
     * @return 失効対象の購入リスト
     */
    List<NotificationCreditPurchaseEntity> findByExpiresAtBeforeAndPaymentStatusAndExpiredAtIsNull(
            LocalDateTime now, NotificationCreditPurchaseStatus status);

    /**
     * 30日前アラート対象を取得する（アラート未送信かつ有効期限30日以内）。
     *
     * @param start  有効期限の範囲開始（今）
     * @param end    有効期限の範囲終了（今+30日）
     * @param status ステータス（PAIDのみ対象）
     * @return 30日前アラート対象リスト
     */
    List<NotificationCreditPurchaseEntity> findByExpiresAtBetweenAndPaymentStatusAndAlertSent30dFalse(
            LocalDateTime start, LocalDateTime end, NotificationCreditPurchaseStatus status);

    /**
     * 7日前アラート対象を取得する（アラート未送信かつ有効期限7日以内）。
     *
     * @param start  有効期限の範囲開始（今）
     * @param end    有効期限の範囲終了（今+7日）
     * @param status ステータス（PAIDのみ対象）
     * @return 7日前アラート対象リスト
     */
    List<NotificationCreditPurchaseEntity> findByExpiresAtBetweenAndPaymentStatusAndAlertSent7dFalse(
            LocalDateTime start, LocalDateTime end, NotificationCreditPurchaseStatus status);
}
