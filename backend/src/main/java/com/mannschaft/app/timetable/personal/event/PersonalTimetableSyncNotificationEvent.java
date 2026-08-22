package com.mannschaft.app.timetable.personal.event;

import com.mannschaft.app.notification.service.NotificationDeliveryRequest;

import java.util.List;

/**
 * F03.15 Phase 4 個人時間割リンク反映の通知発火イベント（Issue #2834 / CMP-056 型確立PR・二段構え第2段）。
 *
 * <p>{@code PersonalTimetableLinkSyncListener}（第1段: 個人スケジュールの save / softDelete を
 * 行う {@code AFTER_COMMIT + REQUIRES_NEW + @Async} リスナー）が、自身の反映トランザクション
 * コミット後に本イベントを publish する。受信者数はチーム時間割へのリンク数に比例して
 * 複数になりうるため、1 イベントに要求一覧をまとめて発行し、
 * {@code PersonalTimetableSyncNotificationListener}（第2段）が {@code NotificationDeliveryRunner}
 * を<b>1件ずつ</b>呼ぶ（{@code event-pool} のキュー消費を1受信者=1タスクにしないため）。
 *
 * @param requests 通知配送要求の一覧
 */
public record PersonalTimetableSyncNotificationEvent(List<NotificationDeliveryRequest> requests) {
}
