package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationSettingsEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationStatus;
import com.mannschaft.app.notification.confirmable.entity.ConfirmedVia;
import com.mannschaft.app.notification.confirmable.entity.UnconfirmedVisibility;
import com.mannschaft.app.notification.confirmable.error.ConfirmableNotificationErrorCode;
import com.mannschaft.app.notification.confirmable.event.ConfirmableNotificationConfirmedEvent;
import com.mannschaft.app.notification.confirmable.event.ConfirmableNotificationCreatedEvent;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRecipientRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.service.NotificationHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * F04.9 確認通知コアサービス。
 *
 * <p>確認通知の送信・確認・キャンセル・詳細取得などの業務ロジックを提供する。</p>
 *
 * <p><b>リマインド分数の3段フォールバック解決ロジック</b>:
 * <ol>
 *   <li>通知個別設定（引数の firstReminderMinutes）</li>
 *   <li>スコープ設定（settings.getDefaultFirstReminderMinutes()）</li>
 *   <li>システムデフォルト（1回目: 180分 / 2回目: 120分）</li>
 * </ol>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConfirmableNotificationService {

    /** リマインド分数のシステムデフォルト値（1回目） */
    private static final int DEFAULT_FIRST_REMINDER_MINUTES = 180;

    /** リマインド分数のシステムデフォルト値（2回目） */
    private static final int DEFAULT_SECOND_REMINDER_MINUTES = 120;

    /** 受信者リストの最大件数 */
    private static final int MAX_RECIPIENT_COUNT = 500;

    private final ConfirmableNotificationRepository notificationRepository;
    private final ConfirmableNotificationRecipientRepository recipientRepository;
    private final ConfirmableNotificationSettingsService settingsService;
    private final UserRepository userRepository;
    private final NotificationHelper notificationHelper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 確認通知を送信する。
     *
     * <p>受信者ごとに確認トークン（UUID）を付与し、リマインド分数を3段フォールバックで解決してから
     * {@code confirmable_notification_recipients} に batch INSERT する。
     * 最後に {@link ConfirmableNotificationCreatedEvent} を発行してF04.3通知基盤に引き渡す。</p>
     *
     * <p><b>F04.9 Phase D（未確認者一覧の可視化）</b>:
     * {@code unconfirmedVisibility} が NULL の場合、スコープ設定の
     * {@code defaultUnconfirmedVisibility} を採用する（2段フォールバック）。
     * 解決された値はエンティティにスナップショットされ、後の設定変更による影響を受けない。</p>
     *
     * @param scopeType          スコープ種別
     * @param scopeId            スコープID
     * @param title              通知タイトル
     * @param body               通知本文（任意）
     * @param priority           優先度
     * @param deadlineAt         確認期限（任意）
     * @param firstReminderMinutes  個別1回目リマインド設定（任意）
     * @param secondReminderMinutes 個別2回目リマインド設定（任意）
     * @param actionUrl          アクションURL（任意）
     * @param templateId         使用テンプレートID（任意）
     * @param unconfirmedVisibility 未確認者リスト公開範囲（任意）。NULL時はスコープ設定の default を使用
     * @param createdByUserId    作成者ユーザーID
     * @param recipientUserIds   受信者ユーザーIDリスト（最大500件）
     * @return 作成された確認通知エンティティ
     */
    @Transactional
    public ConfirmableNotificationEntity send(
            ScopeType scopeType,
            Long scopeId,
            String title,
            String body,
            ConfirmableNotificationPriority priority,
            LocalDateTime deadlineAt,
            Integer firstReminderMinutes,
            Integer secondReminderMinutes,
            String actionUrl,
            Long templateId,
            UnconfirmedVisibility unconfirmedVisibility,
            Long createdByUserId,
            List<Long> recipientUserIds) {

        // 受信者数の上限チェック
        if (recipientUserIds == null || recipientUserIds.isEmpty()) {
            throw new BusinessException(ConfirmableNotificationErrorCode.SEND_FAILED);
        }
        if (recipientUserIds.size() > MAX_RECIPIENT_COUNT) {
            log.warn("受信者数が上限を超えています: count={}, max={}", recipientUserIds.size(), MAX_RECIPIENT_COUNT);
            throw new BusinessException(ConfirmableNotificationErrorCode.SEND_FAILED);
        }

        // スコープ設定を取得（存在しない場合はデフォルト値で作成）
        ConfirmableNotificationSettingsEntity settings =
                settingsService.getOrCreate(scopeType, scopeId);

        // -----------------------------------------------------------------------
        // リマインド分数の3段フォールバック解決
        // 1. 通知個別設定（引数の firstReminderMinutes）
        // 2. スコープ設定（settings.getDefaultFirstReminderMinutes()）
        // 3. システムデフォルト（1回目: 180分 / 2回目: 120分）
        // -----------------------------------------------------------------------
        int resolvedFirstReminder = firstReminderMinutes != null ? firstReminderMinutes
                : (settings.getDefaultFirstReminderMinutes() != null
                        ? settings.getDefaultFirstReminderMinutes()
                        : DEFAULT_FIRST_REMINDER_MINUTES);
        int resolvedSecondReminder = secondReminderMinutes != null ? secondReminderMinutes
                : (settings.getDefaultSecondReminderMinutes() != null
                        ? settings.getDefaultSecondReminderMinutes()
                        : DEFAULT_SECOND_REMINDER_MINUTES);

        // -----------------------------------------------------------------------
        // 未確認者リスト公開範囲の2段フォールバック解決（F04.9 Phase D）
        // 1. リクエスト引数（unconfirmedVisibility）
        // 2. スコープ設定（settings.getDefaultUnconfirmedVisibility()）— 未設定時 CREATOR_AND_ADMIN
        // -----------------------------------------------------------------------
        UnconfirmedVisibility resolvedVisibility = unconfirmedVisibility != null
                ? unconfirmedVisibility
                : (settings.getDefaultUnconfirmedVisibility() != null
                        ? settings.getDefaultUnconfirmedVisibility()
                        : UnconfirmedVisibility.CREATOR_AND_ADMIN);

        // 作成者エンティティの取得
        UserEntity createdByUser = userRepository.findById(createdByUserId).orElse(null);

        // 確認通知エンティティ作成
        ConfirmableNotificationEntity notification = ConfirmableNotificationEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .title(title)
                .body(body)
                .priority(priority != null ? priority : ConfirmableNotificationPriority.NORMAL)
                .deadlineAt(deadlineAt)
                .firstReminderMinutes(firstReminderMinutes)
                .secondReminderMinutes(secondReminderMinutes)
                .actionUrl(actionUrl)
                .templateId(templateId)
                .unconfirmedVisibility(resolvedVisibility)
                .createdBy(createdByUser)
                .totalRecipientCount(recipientUserIds.size())
                .build();

        ConfirmableNotificationEntity savedNotification = notificationRepository.save(notification);

        // 受信者エンティティをバッチ作成（saveAll = batch INSERT）
        List<ConfirmableNotificationRecipientEntity> recipients = recipientUserIds.stream()
                .map(userId -> {
                    UserEntity user = userRepository.getReferenceById(userId);
                    return ConfirmableNotificationRecipientEntity.builder()
                            .confirmableNotification(savedNotification)
                            .user(user)
                            // 各受信者に一意の確認トークンを付与
                            .confirmToken(UUID.randomUUID().toString())
                            .resolvedFirstReminderMinutes(resolvedFirstReminder)
                            .resolvedSecondReminderMinutes(resolvedSecondReminder)
                            .build();
                })
                .collect(Collectors.toList());

        recipientRepository.saveAll(recipients);

        log.info("確認通知送信: notificationId={}, scopeType={}, scopeId={}, recipientCount={}",
                savedNotification.getId(), scopeType, scopeId, recipientUserIds.size());

        // F04.3 通知基盤へのアプリ内通知（送信者には通知しない）
        NotificationPriority notifPriority = toNotificationPriority(savedNotification.getPriority());
        NotificationScopeType notifScopeType = toNotificationScopeType(scopeType);
        notificationHelper.notifyAll(
                recipientUserIds,
                "CONFIRMABLE_NOTIFICATION",
                notifPriority,
                title,
                body != null ? body : "",
                "CONFIRMABLE_NOTIFICATION",
                savedNotification.getId(),
                notifScopeType,
                scopeId,
                actionUrl,
                createdByUserId);

        // ConfirmableNotificationCreatedEvent を発行
        eventPublisher.publishEvent(new ConfirmableNotificationCreatedEvent(
                savedNotification.getId(),
                scopeType,
                scopeId,
                recipientUserIds));

        return savedNotification;
    }

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
     * 確認通知の詳細を取得する。
     *
     * @param notificationId 確認通知ID
     * @return 確認通知エンティティ
     */
    @Transactional(readOnly = true)
    public ConfirmableNotificationEntity getDetail(Long notificationId) {
        return notificationRepository.findById(notificationId)
                .orElseThrow(() -> new BusinessException(ConfirmableNotificationErrorCode.NOT_FOUND));
    }

    /**
     * 確認通知の受信者一覧を取得する（ADMIN+ 用・全件）。
     *
     * <p>呼び出し側で ADMIN+ 権限チェック済みであること。
     * F04.9 Phase D の MEMBER 視点アクセスは
     * {@link #getRecipientsForMember(Long, Long)} を使用すること。</p>
     *
     * @param notificationId 確認通知ID
     * @return 受信者エンティティリスト（除外者・確認済みも含む全件）
     */
    @Transactional(readOnly = true)
    public List<ConfirmableNotificationRecipientEntity> getRecipients(Long notificationId) {
        // 通知の存在確認
        if (!notificationRepository.existsById(notificationId)) {
            throw new BusinessException(ConfirmableNotificationErrorCode.NOT_FOUND);
        }
        return recipientRepository.findByConfirmableNotificationId(notificationId);
    }

    /**
     * MEMBER 視点で確認通知の未確認者一覧を取得する（F04.9 Phase D）。
     *
     * <p>認可判定:
     * <ol>
     *   <li>通知が存在し、{@code unconfirmedVisibility = ALL_MEMBERS} であること</li>
     *   <li>呼び出しユーザーが当通知の受信者であること（除外者は不可）</li>
     * </ol>
     * いずれかを満たさない場合は {@link CommonErrorCode#COMMON_002}（403）を投げる。</p>
     *
     * <p>戻り値は <b>未確認かつ非除外</b> の受信者のみ。Mapper の
     * {@code toRecipientPublicResponseList} で confirmedAt / confirmedVia / excludedAt をマスクして返すこと。</p>
     *
     * @param notificationId  確認通知ID
     * @param requesterUserId リクエスト元ユーザーID
     * @return 未確認受信者エンティティリスト（マスク前）
     */
    @Transactional(readOnly = true)
    public List<ConfirmableNotificationRecipientEntity> getRecipientsForMember(
            Long notificationId, Long requesterUserId) {
        // 通知の存在確認
        ConfirmableNotificationEntity notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new BusinessException(ConfirmableNotificationErrorCode.NOT_FOUND));

        // 公開範囲チェック: ALL_MEMBERS 以外は 403
        if (notification.getUnconfirmedVisibility() != UnconfirmedVisibility.ALL_MEMBERS) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        // 呼び出しユーザーが受信者かつ非除外であることを確認
        List<ConfirmableNotificationRecipientEntity> allRecipients =
                recipientRepository.findByConfirmableNotificationId(notificationId);
        boolean isRecipient = allRecipients.stream()
                .anyMatch(r -> r.getUser().getId().equals(requesterUserId) && !r.isExcluded());
        if (!isRecipient) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        // 未確認かつ非除外の受信者のみ返す
        return allRecipients.stream()
                .filter(r -> !Boolean.TRUE.equals(r.getIsConfirmed()))
                .filter(r -> !r.isExcluded())
                .collect(Collectors.toList());
    }

    /**
     * スコープ内の確認通知一覧を取得する（作成日時降順）。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return 確認通知エンティティリスト
     */
    @Transactional(readOnly = true)
    public List<ConfirmableNotificationEntity> listByScope(ScopeType scopeType, Long scopeId) {
        return notificationRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(scopeType, scopeId);
    }

    /**
     * ユーザーの未確認通知一覧を取得する（受信者視点）。
     *
     * @param userId ユーザーID
     * @return 未確認受信者エンティティリスト
     */
    @Transactional(readOnly = true)
    public List<ConfirmableNotificationRecipientEntity> listPending(Long userId) {
        return recipientRepository.findByUserIdAndIsConfirmedFalseAndExcludedAtIsNull(userId);
    }

    // =========================================================================
    // プライベートヘルパーメソッド
    // =========================================================================

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
