package com.mannschaft.app.ticket.event;

/**
 * チケット期限切れ事前通知（30/7/3/1 日前）の配送要求イベント（Issue #2997 / CMP-260827-1152 G6）。
 *
 * <p>{@code TicketExpiryBatchService#sendExpiryNotifications} のトランザクション内で publish し、
 * {@link TicketExpiryNotificationListener} が {@code AFTER_COMMIT} で受け取る。</p>
 *
 * @param bookId     回数券ID（通知の source）
 * @param userId     所有者（受信者）
 * @param daysBefore 期限までの日数（本文に埋め込む）
 */
public record TicketExpiryWarningNotificationEvent(Long bookId, Long userId, int daysBefore) {
}
