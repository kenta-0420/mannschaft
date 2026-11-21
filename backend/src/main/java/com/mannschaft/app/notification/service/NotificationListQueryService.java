package com.mannschaft.app.notification.service;

import com.mannschaft.app.notification.dto.NotificationResponse;
import com.mannschaft.app.scopefolder.entity.enums.ScopeType;
import com.mannschaft.app.scopefolder.service.MyScopeFolderQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 通知一覧 API の取得処理を取りまとめる。
 *
 * <p>フォルダの所有者確認と通知検索が個別の読み取りトランザクションで完結するよう、
 * コントローラーからトランザクションの外で呼び出す。</p>
 */
@Service
@RequiredArgsConstructor
public class NotificationListQueryService {

    private final MyScopeFolderQueryService myScopeFolderQueryService;
    private final NotificationService notificationService;

    public Page<NotificationResponse> listNotifications(
            Long userId, Long folderId, ScopeType scopeType, Boolean isRead, Pageable pageable) {
        if (folderId == null || scopeType == null) {
            return notificationService.listNotifications(userId, isRead, pageable);
        }

        List<Long> scopeIds = myScopeFolderQueryService.getScopeIdsInFolder(userId, folderId);
        return notificationService.listNotificationsInScopes(
                userId, NotificationService.toNotificationScopeType(scopeType), scopeIds, isRead, pageable);
    }
}
