package com.mannschaft.app.notification.outbox;

import java.util.UUID;

/**
 * {@link NotificationOutboxSource#claim} が返す、claim 済みの outbox の1行（docs/architecture/notification_outbox.md §4.2）。
 *
 * @param id             outbox 行の ID
 * @param claimToken     この claim の世代（印付けはこの値が一致するときだけ当たる）
 * @param kind           取り込みの種類（{@code message_kind}）
 * @param payloadVersion {@code payload_json} の版
 * @param payloadJson    取り込みの依頼（JSON）
 * @param attemptCount   これまでの失敗回数（claim 時点）
 */
public record NotificationOutboxMessage(UUID id,
                                        UUID claimToken,
                                        String kind,
                                        int payloadVersion,
                                        String payloadJson,
                                        int attemptCount) {
}
