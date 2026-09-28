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
 * <p>ファサード {@link ConfirmableNotificationService} から委譲される確認系処理を実装する。</p>
 *
 * <p><b>CMP-260920-1040（軍議第8版確定稿 §8.2・§9.2・§9.3・§10・§11.1）</b>: 親行を扱うすべての操作は、
 * そのトランザクションで最初に親を読む操作を {@code findByIdForUpdate} にする（ロック順序は
 * 「親の行 → 受信者の行」）。完了判定は受信者を全件読み込む COUNT/List 走査ではなく、ロックした
 * 親行の {@code unconfirmedCount} カウンタだけで行う（confirm 1回あたりのクエリ数が受信者数に
 * 比例しないようにする・AC-42）。confirm と confirmByToken は本質的に同じ判定ロジックを共有する
 * （private {@link #doConfirm} に集約・§9.3）。</p>
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
        // §11.1: このトランザクションで最初に親を読む操作を findByIdForUpdate にする。
        ConfirmableNotificationEntity notification = notificationRepository.findByIdForUpdate(notificationId)
                .orElseThrow(() -> new BusinessException(ConfirmableNotificationErrorCode.NOT_FOUND));

        if (!notification.isActive()) {
            throw new BusinessException(ConfirmableNotificationErrorCode.ALREADY_CANCELLED);
        }

        ConfirmableNotificationRecipientEntity recipient =
                recipientRepository.findByNotificationIdAndUserIdForUpdate(notificationId, userId)
                        .orElseThrow(() -> new BusinessException(ConfirmableNotificationErrorCode.RECIPIENT_NOT_FOUND));

        doConfirm(notification, recipient, ConfirmedVia.APP);

        log.info("確認通知確認（APP）: notificationId={}, userId={}", notificationId, userId);

        eventPublisher.publishEvent(new ConfirmableNotificationConfirmedEvent(
                notificationId, userId, recipient.getConfirmedAt()));
    }

    /**
     * トークンURL経由で確認通知を確認する（認証不要）。
     *
     * <p>軍議第8版確定稿 §10.1 confirmByToken の手順:
     * <ol>
     *   <li>トークンから notification_id を引く（不変列のみ・通常の SELECT でよい）</li>
     *   <li>親の行を FOR UPDATE でロックする</li>
     *   <li>受信者の行をトークンで FOR UPDATE して読み直す</li>
     *   <li>確認済みにし、カウンタを減らす</li>
     *   <li>完了を判定する</li>
     * </ol>
     * ロック順序は「親の行 → 受信者の行」で §9.2 の規約と揃える。</p>
     *
     * @param confirmToken 確認トークン（UUID文字列）
     */
    @Transactional
    public void confirmByToken(String confirmToken) {
        // 手順1: トークンから notification_id を引く（不変列のみ）。
        ConfirmableNotificationRecipientEntity unlockedRecipient =
                recipientRepository.findByConfirmToken(confirmToken)
                        .orElseThrow(() -> new BusinessException(ConfirmableNotificationErrorCode.INVALID_TOKEN));
        Long notificationId = unlockedRecipient.getConfirmableNotification().getId();

        // 手順2: 親の行を FOR UPDATE でロックする。
        ConfirmableNotificationEntity notification = notificationRepository.findByIdForUpdate(notificationId)
                .orElseThrow(() -> new BusinessException(ConfirmableNotificationErrorCode.NOT_FOUND));

        if (!notification.isActive()) {
            throw new BusinessException(ConfirmableNotificationErrorCode.ALREADY_CANCELLED);
        }

        // 手順3: 受信者の行をトークンで FOR UPDATE して読み直す（ロック取得前の読み取りは使い回さない）。
        ConfirmableNotificationRecipientEntity recipient =
                recipientRepository.findByConfirmTokenForUpdate(confirmToken)
                        .orElseThrow(() -> new BusinessException(ConfirmableNotificationErrorCode.INVALID_TOKEN));

        doConfirm(notification, recipient, ConfirmedVia.TOKEN);

        log.info("確認通知確認（TOKEN）: notificationId={}, userId={}",
                notification.getId(), recipient.getUser().getId());

        eventPublisher.publishEvent(new ConfirmableNotificationConfirmedEvent(
                notification.getId(),
                recipient.getUser().getId(),
                recipient.getConfirmedAt()));
    }

    /**
     * confirm / confirmByToken 共通の確認処理（§9.3）。
     *
     * <p>手順4〜5に相当: 除外・二重確認チェック → 確認記録 → unconfirmed_count 減算 →
     * 配信中（delivery_status != DELIVERED）は完了判定を保留する（§8.2）。</p>
     */
    private void doConfirm(ConfirmableNotificationEntity notification,
            ConfirmableNotificationRecipientEntity recipient, ConfirmedVia via) {
        if (recipient.isExcluded()) {
            throw new BusinessException(via == ConfirmedVia.TOKEN
                    ? ConfirmableNotificationErrorCode.INVALID_TOKEN
                    : ConfirmableNotificationErrorCode.RECIPIENT_NOT_FOUND);
        }
        if (Boolean.TRUE.equals(recipient.getIsConfirmed())) {
            throw new BusinessException(ConfirmableNotificationErrorCode.ALREADY_CONFIRMED);
        }

        recipient.confirm(via);
        recipientRepository.save(recipient);

        // §10.1: カウンタはロックした親行に対してのみ更新する。
        notification.decrementUnconfirmedCount();

        // §8.2: 配信が終わる（DELIVERED）まで完了判定を保留する。
        if (notification.isReadyToComplete()) {
            notification.complete();
            log.info("確認通知完了（全員確認）: notificationId={}", notification.getId());
        }
        notificationRepository.save(notification);
    }

    /**
     * 確認通知をキャンセルする（ADMIN操作）。
     *
     * <p>§10.2: 親の行を FOR UPDATE でロックしてから status を読み直し、ACTIVE のときだけ遷移させる
     * （§9.2 の finish 等と同じロック順序に統一。AC-45・AC-64・AC-66）。</p>
     *
     * @param notificationId    確認通知ID
     * @param cancelledByUserId キャンセル実行者のユーザーID
     */
    @Transactional
    public void cancel(Long notificationId, Long cancelledByUserId) {
        ConfirmableNotificationEntity notification = notificationRepository.findByIdForUpdate(notificationId)
                .orElseThrow(() -> new BusinessException(ConfirmableNotificationErrorCode.NOT_FOUND));

        // ロック取得後に再判定する。ACTIVE 以外（CANCELLED / COMPLETED / EXPIRED）はすべて拒否。
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
