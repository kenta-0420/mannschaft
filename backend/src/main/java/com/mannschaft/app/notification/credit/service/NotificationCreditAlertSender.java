package com.mannschaft.app.notification.credit.service;

import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 通知クレジット残高マイナスアラートの非同期送信 Bean（CMP-035）。
 *
 * <p>{@code @Async} はプロキシ経由の呼び出しでのみ有効になるため、呼び出し元と別 Bean に切り出す
 * （同一 Bean 内の自己呼び出しではプロキシを経由せず {@code @Async} が無視されるため）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationCreditAlertSender {

    private final NotificationHelper notificationHelper;
    private final UserRoleRepository userRoleRepository;

    /**
     * 残高マイナス警告をADMINへ非同期送信する。
     *
     * @param organizationId 組織ID
     * @param creditBalance  現在のクレジット残高（負の値）
     */
    @Async
    public void sendNegativeBalanceAlert(Long organizationId, Long creditBalance) {
        try {
            List<Long> adminUserIds = userRoleRepository.findAdminUserIdsByOrganizationId(organizationId);
            if (adminUserIds.isEmpty()) {
                return;
            }
            notificationHelper.notifyAll(
                    adminUserIds,
                    "NOTIFICATION_CREDIT_NEGATIVE",
                    "通知クレジット残高がマイナスです",
                    "猶予期間中の超過分が相殺された結果、クレジット残高がマイナスになりました。"
                            + "残高: " + creditBalance + "通。クレジットを購入してください。",
                    "NOTIFICATION_CREDIT",
                    organizationId,
                    NotificationScopeType.ORGANIZATION,
                    organizationId,
                    "/organizations/" + organizationId + "/settings/notification-credits",
                    null
            );
            log.info("残高マイナスアラート送信: organizationId={}, balance={}", organizationId, creditBalance);
        } catch (Exception e) {
            log.error("残高マイナスアラート送信失敗: organizationId={}", organizationId, e);
        }
    }
}
