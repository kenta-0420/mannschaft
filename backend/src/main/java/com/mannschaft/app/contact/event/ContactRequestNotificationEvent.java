package com.mannschaft.app.contact.event;

import com.mannschaft.app.notification.service.NotificationDeliveryRequest;

/**
 * 連絡先申請ドメインの通知発火イベント（Issue #2834 / CMP-056 型確立PR）。
 *
 * <p>{@code ContactRequestService#sendRequest} / {@code #acceptRequest} は業務トランザクションの
 * 内側で本イベントを publish するだけに留め、通知の実生成は {@code ContactRequestNotificationListener}
 * が {@code AFTER_COMMIT} で受け取ってから行う。これにより「申請 INSERT はコミットされたのに、通知が
 * 未コミットの申請行を可視性ガードで拒否される」逆転（AC-3 が実証する新規行参照ケース）を防ぐ。</p>
 */
public record ContactRequestNotificationEvent(NotificationDeliveryRequest request) {
}
