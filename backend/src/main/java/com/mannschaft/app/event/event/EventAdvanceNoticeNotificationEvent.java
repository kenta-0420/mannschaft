package com.mannschaft.app.event.event;

import com.mannschaft.app.notification.service.NotificationDeliveryRequest;

import java.util.List;

/**
 * F03.12 事前遅刻・欠席連絡の通知発火イベント（Issue #2834 / CMP-056 型確立PR）。
 *
 * <p>{@code EventRsvpService#submitLateNotice} / {@code #submitAbsenceNotice} は業務トランザクション
 * の内側で本イベントを publish するだけに留める。宛先は主催者 1 名 + 見守り者 0〜N 名のため、
 * 複数件を 1 イベントにまとめて発行し、配送リスナー
 * （{@code EventAdvanceNoticeNotificationListener}）が受信者ごとに
 * {@code NotificationDeliveryRunner#sendOne} を<b>1件ずつ</b>呼ぶ（AC-7: リスナー全体を1つの
 * {@code REQUIRES_NEW} で包まない）。
 *
 * @param requests 通知配送要求の一覧（主催者 + 見守り者）
 */
public record EventAdvanceNoticeNotificationEvent(List<NotificationDeliveryRequest> requests) {
}
