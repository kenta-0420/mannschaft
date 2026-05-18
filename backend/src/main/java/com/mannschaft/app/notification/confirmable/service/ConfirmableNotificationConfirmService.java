package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationStatus;
import com.mannschaft.app.notification.confirmable.entity.ConfirmedVia;
import com.mannschaft.app.notification.confirmable.error.ConfirmableNotificationErrorCode;
import com.mannschaft.app.notification.confirmable.event.ConfirmableNotificationConfirmedEvent;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRecipientRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * F04.9 確認通知の確認・キャンセル・リマインド再送を担当するサービス。
 *
 * <p>ファサード {@link ConfirmableNotificationService} から委譲される確認系処理を実装する。
 * ロジック・例外条件・イベント発行は分割前の {@code ConfirmableNotificationService} と同一。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfirmableNotificationConfirmService {

    private final ConfirmableNotificationRepository notificationRepository;
    private final ConfirmableNotificationRecipientRepository recipientRepository;
    private final UserRepository userRepository;
    private final NotificationHelper notificationHelper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 認証済みユーザーがアプリ内から確認通知を確認する。
     *
     * @param notificationId 確認通知ID
     * @param userId         確認するユーザーID
     */
    @Transactional
    public void confirm(Long notificationId, Long userId) {
        // 通知の存在チェック
        ConfirmableNotificationEntity notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new BusinessException(ConfirmableNotificationErrorCode.NOT_FOUND));

        // ACTIVE 状態チェック（キャンセル・期限切れ・完了済みは確認不可）
        if (!notification.isActive()) {
            throw new BusinessException(ConfirmableNotificationErrorCode.ALREADY_CANCELLED);
        }

        // 受信者レコードの取得
        List<ConfirmableNotificationRecipientEntity> allRecipients =
                recipientRepository.findByConfirmableNotificationId(notificationId);
        ConfirmableNotificationRecipientEntity recipient = allRecipients.stream()
                .filter(r -> r.getUser().getId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ConfirmableNotificationErrorCode.RECIPIENT_NOT_FOUND));

        // 除外済みチェック
        if (recipient.isExcluded()) {
            throw new BusinessException(ConfirmableNotificationErrorCode.RECIPIENT_NOT_FOUND);
        }

        // 二重確認チェック
        if (Boolean.TRUE.equals(recipient.getIsConfirmed())) {
            throw new BusinessException(ConfirmableNotificationErrorCode.ALREADY_CONFIRMED);
        }

        // アプリ内確認として記録
        recipient.confirm(ConfirmedVia.APP);
        recipientRepository.save(recipient);

        log.info("確認通知確認（APP）: notificationId={}, userId={}", notificationId, userId);

        // 全受信者（除外者を除く）が確認済みになった場合は通知を完了状態にする
        checkAndCompleteIfAllConfirmed(notification, allRecipients, recipient);

        // ConfirmableNotificationConfirmedEvent を発行（AFTER_COMMIT でリスナーが受け取る）
        eventPublisher.publishEvent(new ConfirmableNotificationConfirmedEvent(
                notificationId, userId, recipient.getConfirmedAt()));
    }

    /**
     * トークンURL経由で確認通知を確認する（認証不要）。
     *
     * @param confirmToken 確認トークン（UUID文字列）
     */
    @Transactional
    public void confirmByToken(String confirmToken) {
        // トークンで受信者を検索
        ConfirmableNotificationRecipientEntity recipient =
                recipientRepository.findByConfirmToken(confirmToken)
                        .orElseThrow(() -> new BusinessException(ConfirmableNotificationErrorCode.INVALID_TOKEN));

        // 除外済みチェック
        if (recipient.isExcluded()) {
            throw new BusinessException(ConfirmableNotificationErrorCode.INVALID_TOKEN);
        }

        // 二重確認チェック
        if (Boolean.TRUE.equals(recipient.getIsConfirmed())) {
            throw new BusinessException(ConfirmableNotificationErrorCode.ALREADY_CONFIRMED);
        }

        ConfirmableNotificationEntity notification = recipient.getConfirmableNotification();

        // ACTIVE 状態チェック
        if (!notification.isActive()) {
            throw new BusinessException(ConfirmableNotificationErrorCode.ALREADY_CANCELLED);
        }

        // トークン経由での確認として記録
        recipient.confirm(ConfirmedVia.TOKEN);
        recipientRepository.save(recipient);

        log.info("確認通知確認（TOKEN）: notificationId={}, userId={}",
                notification.getId(), recipient.getUser().getId());

        // 全受信者確認済み判定
        List<ConfirmableNotificationRecipientEntity> allRecipients =
                recipientRepository.findByConfirmableNotificationId(notification.getId());
        checkAndCompleteIfAllConfirmed(notification, allRecipients, recipient);

        // イベント発行
        eventPublisher.publishEvent(new ConfirmableNotificationConfirmedEvent(
                notification.getId(),
                recipient.getUser().getId(),
                recipient.getConfirmedAt()));
    }

    /**
     * 確認通知をキャンセルする（ADMIN操作）。
     *
     * @param notificationId    確認通知ID
     * @param cancelledByUserId キャンセル実行者のユーザーID
     */
    @Transactional
    public void cancel(Long notificationId, Long cancelledByUserId) {
        ConfirmableNotificationEntity notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new BusinessException(ConfirmableNotificationErrorCode.NOT_FOUND));

        // 既にキャンセル済みの場合はエラー
        if (notification.getStatus() == ConfirmableNotificationStatus.CANCELLED) {
            throw new BusinessException(ConfirmableNotificationErrorCode.ALREADY_CANCELLED);
        }

        // ACTIVE 以外（COMPLETED / EXPIRED）もキャンセル不可
        if (!notification.isActive()) {
            throw new BusinessException(ConfirmableNotificationErrorCode.ALREADY_CANCELLED);
        }

        UserEntity cancelledBy = userRepository.findById(cancelledByUserId).orElse(null);
        notification.cancel(cancelledBy);
        notificationRepository.save(notification);

        log.info("確認通知キャンセル: notificationId={}, cancelledByUserId={}",
                notificationId, cancelledByUserId);
    }

    /**
     * 手動リマインドを再送する（ADMIN操作）。
     *
     * <p>ACTIVE 状態の通知に対して、未確認の受信者全員にリマインドを再送する。</p>
     *
     * @param notificationId 確認通知ID
     */
    @Transactional
    public void resendReminder(Long notificationId) {
        ConfirmableNotificationEntity notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new BusinessException(ConfirmableNotificationErrorCode.NOT_FOUND));

        if (!notification.isActive()) {
            throw new BusinessException(ConfirmableNotificationErrorCode.ALREADY_CANCELLED);
        }

        // 未確認かつ除外されていない受信者を取得
        List<ConfirmableNotificationRecipientEntity> unconfirmedRecipients =
                recipientRepository.findActiveUnconfirmedByNotificationId(notificationId);

        if (unconfirmedRecipients.isEmpty()) {
            log.info("手動リマインド再送: 未確認受信者なし notificationId={}", notificationId);
            return;
        }

        // 未確認受信者のユーザーIDを収集
        List<Long> targetUserIds = unconfirmedRecipients.stream()
                .map(r -> r.getUser().getId())
                .collect(Collectors.toList());

        // F04.3 通知基盤経由でリマインドを一括送信
        NotificationScopeType notifScopeType = toNotificationScopeType(notification.getScopeType());
        notificationHelper.notifyAll(
                targetUserIds,
                "CONFIRMABLE_NOTIFICATION_REMINDER",
                toNotificationPriority(notification.getPriority()),
                notification.getTitle(),
                notification.getBody() != null ? notification.getBody() : "",
                "CONFIRMABLE_NOTIFICATION",
                notificationId,
                notifScopeType,
                notification.getScopeId(),
                notification.getActionUrl(),
                null);

        log.info("手動リマインド再送: notificationId={}, targetCount={}", notificationId, targetUserIds.size());
    }

    /**
     * 全受信者（除外者を除く）が確認済みの場合は通知を完了状態にする。
     *
     * @param notification   対象確認通知
     * @param allRecipients  全受信者リスト
     * @param justConfirmed  今回確認された受信者（already-confirmed 状態に反映済み）
     */
    private void checkAndCompleteIfAllConfirmed(
            ConfirmableNotificationEntity notification,
            List<ConfirmableNotificationRecipientEntity> allRecipients,
            ConfirmableNotificationRecipientEntity justConfirmed) {

        // 除外者を除いた受信者の中に未確認者が残っていないか確認
        boolean allConfirmed = allRecipients.stream()
                .filter(r -> !r.isExcluded())
                .allMatch(r -> {
                    // 今回確認されたレシピエントは確認済みとして扱う（save前でも）
                    if (r.getId().equals(justConfirmed.getId())) {
                        return true;
                    }
                    return Boolean.TRUE.equals(r.getIsConfirmed());
                });

        if (allConfirmed) {
            notification.complete();
            notificationRepository.save(notification);
            log.info("確認通知完了（全員確認）: notificationId={}", notification.getId());
        }
    }

    /**
     * 確認通知の優先度を F04.3 通知基盤の優先度に変換する。
     */
    private NotificationPriority toNotificationPriority(ConfirmableNotificationPriority priority) {
        return switch (priority) {
            case URGENT -> NotificationPriority.URGENT;
            case HIGH -> NotificationPriority.HIGH;
            case NORMAL -> NotificationPriority.NORMAL;
        };
    }

    /**
     * ScopeType を NotificationScopeType に変換する。
     */
    private NotificationScopeType toNotificationScopeType(ScopeType scopeType) {
        return switch (scopeType) {
            case TEAM -> NotificationScopeType.TEAM;
            case ORGANIZATION -> NotificationScopeType.ORGANIZATION;
            case PLATFORM -> NotificationScopeType.SYSTEM;
            case COMMITTEE -> NotificationScopeType.COMMITTEE;
        };
    }
}
