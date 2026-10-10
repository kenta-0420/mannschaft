package com.mannschaft.app.notification.outbox;

import org.springframework.stereotype.Component;

/**
 * {@link NotificationOutboxPayload} と {@code payload_json} の版つき変換（docs/architecture/notification_outbox.md §6）。
 *
 * <p>版の展開は reader を先、writer を後の2段階で行う。relay は claim した行の版を {@link #supports(int)} で確かめ、
 * 読めない版は失敗に数えず PENDING のまま先送りする（ローリングデプロイ中に古いノードが新しい版を掴んだ場合）。</p>
 *
 * <p>【試練の骨格・出陣で実装】</p>
 */
@Component
public class NotificationOutboxPayloadCodec {

    /** writer が書く現在の版。 */
    public static final int CURRENT_VERSION = 1;

    /** この版を読めるか。 */
    public boolean supports(int payloadVersion) {
        throw new UnsupportedOperationException("出陣で実装: NotificationOutboxPayloadCodec#supports");
    }

    /** 現在の版（{@link #CURRENT_VERSION}）で JSON にする。 */
    public String encode(NotificationOutboxPayload payload) {
        throw new UnsupportedOperationException("出陣で実装: NotificationOutboxPayloadCodec#encode");
    }

    /** 指定の版の JSON を読む。読めない版なら {@link IllegalArgumentException}。 */
    public NotificationOutboxPayload decode(int payloadVersion, String payloadJson) {
        throw new UnsupportedOperationException("出陣で実装: NotificationOutboxPayloadCodec#decode");
    }
}
