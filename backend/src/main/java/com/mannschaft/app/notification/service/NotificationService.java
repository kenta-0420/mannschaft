package com.mannschaft.app.notification.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.NotificationSourceTypeMapper;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.notification.NotificationErrorCode;
import com.mannschaft.app.notification.NotificationMapper;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.dto.NotificationResponse;
import com.mannschaft.app.notification.dto.NotificationStatsResponse;
import com.mannschaft.app.notification.dto.SnoozeRequest;
import com.mannschaft.app.notification.dto.UnreadCountResponse;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.notification.repository.PushSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 通知サービス。通知のCRUD・既読管理・スヌーズを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final NotificationMapper notificationMapper;

    /**
     * F00 Phase F セキュリティ漏れ修正で導入。通知発行先ユーザーが
     * 通知のソースコンテンツ ({@code sourceType} + {@code sourceId}) を閲覧可能か
     * を判定し、不可の場合は通知作成自体をスキップする (fail-soft, §11.1)。
     *
     * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §11.1 / §13.5。
     */
    private final ContentVisibilityChecker visibilityChecker;

    /**
     * ユーザーの通知一覧をページング取得する。
     *
     * @param userId   ユーザーID
     * @param pageable ページング情報
     * @return 通知レスポンスのページ
     */
    public Page<NotificationResponse> listNotifications(Long userId, Pageable pageable) {
        Page<NotificationEntity> page = notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        return page.map(notificationMapper::toNotificationResponse);
    }

    /**
     * ユーザーの未読通知件数を取得する。
     *
     * @param userId ユーザーID
     * @return 未読件数レスポンス
     */
    public UnreadCountResponse getUnreadCount(Long userId) {
        long count = notificationRepository.countByUserIdAndIsReadFalse(userId);
        return new UnreadCountResponse(count);
    }

    /**
     * 通知を既読にする。
     *
     * @param userId         ユーザーID
     * @param notificationId 通知ID
     * @return 更新された通知レスポンス
     */
    @Transactional
    public NotificationResponse markAsRead(Long userId, Long notificationId) {
        NotificationEntity entity = findNotificationOrThrow(userId, notificationId);

        if (entity.isAlreadyRead()) {
            throw new BusinessException(NotificationErrorCode.ALREADY_READ);
        }

        entity.markAsRead();
        NotificationEntity saved = notificationRepository.save(entity);
        log.info("通知既読: userId={}, notificationId={}", userId, notificationId);
        return notificationMapper.toNotificationResponse(saved);
    }

    /**
     * 通知を未読に戻す。
     *
     * @param userId         ユーザーID
     * @param notificationId 通知ID
     * @return 更新された通知レスポンス
     */
    @Transactional
    public NotificationResponse markAsUnread(Long userId, Long notificationId) {
        NotificationEntity entity = findNotificationOrThrow(userId, notificationId);

        if (!entity.isAlreadyRead()) {
            throw new BusinessException(NotificationErrorCode.ALREADY_UNREAD);
        }

        entity.markAsUnread();
        NotificationEntity saved = notificationRepository.save(entity);
        log.info("通知未読戻し: userId={}, notificationId={}", userId, notificationId);
        return notificationMapper.toNotificationResponse(saved);
    }

    /**
     * 通知をスヌーズする。
     *
     * @param userId         ユーザーID
     * @param notificationId 通知ID
     * @param request        スヌーズリクエスト
     * @return 更新された通知レスポンス
     */
    @Transactional
    public NotificationResponse snoozeNotification(Long userId, Long notificationId, SnoozeRequest request) {
        NotificationEntity entity = findNotificationOrThrow(userId, notificationId);

        if (request.getSnoozedUntil().isBefore(LocalDateTime.now())) {
            throw new BusinessException(NotificationErrorCode.INVALID_SNOOZE_TIME);
        }

        entity.snooze(request.getSnoozedUntil());
        NotificationEntity saved = notificationRepository.save(entity);
        log.info("通知スヌーズ: userId={}, notificationId={}, until={}", userId, notificationId, request.getSnoozedUntil());
        return notificationMapper.toNotificationResponse(saved);
    }

    /**
     * ユーザーの未読通知を全て既読にする。
     *
     * @param userId ユーザーID
     * @return 既読にした件数
     */
    @Transactional
    public int markAllAsRead(Long userId) {
        int count = notificationRepository.markAllAsReadByUserId(userId);
        log.info("通知全件既読: userId={}, count={}", userId, count);
        return count;
    }

    /**
     * 通知を作成する（内部利用）。
     *
     * @param userId           宛先ユーザーID
     * @param notificationType 通知種別
     * @param priority         優先度
     * @param title            タイトル
     * @param body             本文
     * @param sourceType       ソース種別
     * @param sourceId         ソースID
     * @param scopeType        スコープ種別
     * @param scopeId          スコープID
     * @param actionUrl        アクションURL
     * @param actorId          実行者ID
     * @return 作成された通知エンティティ
     */
    @Transactional
    public NotificationEntity createNotification(Long userId, String notificationType,
                                                  NotificationPriority priority, String title, String body,
                                                  String sourceType, Long sourceId,
                                                  NotificationScopeType scopeType, Long scopeId,
                                                  String actionUrl, Long actorId) {
        // ----------------------------------------------------------------
        // F00 Phase F: 通知発行前の visibility ガード (§11.1)
        // ----------------------------------------------------------------
        // sourceType を ReferenceType にマップして閲覧権を確認。閲覧不可の
        // ユーザーには通知を作らない (受信者リスト確定後の filterAccessible
        // 相当を、単発 createNotification 経路でも担保する)。
        //
        // fail-soft: ReferenceType に対応しない sourceType (例: MEMBER_PAYMENT)
        // や、sourceId が null の通知は visibility 判定対象外として通過させる。
        // これにより既存の Resolver 未配備 sourceType を破壊しない。
        if (!isAccessible(sourceType, sourceId, userId)) {
            log.warn("通知作成スキップ (visibility deny): userId={}, type={}, sourceType={}, sourceId={}",
                    userId, notificationType, sourceType, sourceId);
            return null;
        }

        NotificationEntity entity = NotificationEntity.builder()
                .userId(userId)
                .notificationType(notificationType)
                .priority(priority)
                .title(title)
                .body(body)
                .sourceType(sourceType)
                .sourceId(sourceId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .actionUrl(actionUrl)
                .actorId(actorId)
                .build();

        NotificationEntity saved = notificationRepository.save(entity);
        log.info("通知作成: userId={}, type={}, notificationId={}", userId, notificationType, saved.getId());
        return saved;
    }

    /**
     * 通知ソースに対する受信者の閲覧可否を判定する。
     *
     * <p>F00 Phase F セキュリティガード (§11.1)。{@code sourceType} を
     * {@link ReferenceType} に解決できない、または {@code sourceId} が null の
     * 通知は対象外として true を返す (fail-soft)。
     *
     * @param sourceType 通知 sourceType
     * @param sourceId   通知 sourceId
     * @param userId     受信者 userId
     * @return アクセス可能または判定対象外なら true
     */
    private boolean isAccessible(String sourceType, Long sourceId, Long userId) {
        if (sourceId == null) {
            return true;
        }
        Optional<ReferenceType> refType = NotificationSourceTypeMapper.resolve(sourceType);
        if (refType.isEmpty()) {
            return true;
        }
        return visibilityChecker.canView(refType.get(), sourceId, userId);
    }

    /**
     * 管理者向け通知統計を取得する。
     *
     * @return 通知統計レスポンス
     */
    public NotificationStatsResponse getStats() {
        long total = notificationRepository.count();
        long totalSubscriptions = pushSubscriptionRepository.count();

        // 全ユーザーの合計未読数・既読数をカスタムクエリで集計
        long unread = notificationRepository.countByIsReadFalse();
        long read = total - unread;

        return new NotificationStatsResponse(total, unread, read, totalSubscriptions);
    }

    /**
     * 通知を取得する。存在しない場合は例外をスローする。
     */
    private NotificationEntity findNotificationOrThrow(Long userId, Long notificationId) {
        return notificationRepository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new BusinessException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
    }
}
