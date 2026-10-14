package com.mannschaft.app.ticket.event;

/**
 * チケット期限切れ（ACTIVE から EXPIRED への遷移）通知の配送要求イベント（Issue #2997 / CMP-260827-1152 G6）。
 *
 * <p>{@code TicketExpiryRunner#expireOne} が 1 冊ぶんの遷移を独立トランザクションで確定するときに publish し、
 * {@link TicketExpiryNotificationListener} が {@code AFTER_COMMIT} で受け取る。</p>
 *
 * @param bookId 回数券ID（通知の source。遷移は行を削除しないため生存している）
 * @param userId 所有者（受信者）
 */
public record TicketExpiredNotificationEvent(Long bookId, Long userId) {
}
