package com.mannschaft.app.websocket.relay;

/**
 * WebSocket 中継（relay）で用いる固定定数（設計書 §4.2 / §4.4 / §4.5.1）。
 *
 * <p>チャネル名・リレーマーカー・メッセージ種別はすべて<b>コード内定数</b>として固定する
 * （プロパティ化しない — 全ノードが同一値であることを設定ミスの余地なく担保するため・§4.2）。</p>
 */
public final class WebSocketRelayConstants {

    private WebSocketRelayConstants() {
    }

    /** {@code /topic/...} ブロードキャスト中継チャネル（§4.2）。 */
    public static final String CHANNEL_BROADCAST = "mannschaft:ws:relay:broadcast";

    /** {@code /user/.../queue/...} ユーザー宛中継チャネル（§4.2 / §4.3）。 */
    public static final String CHANNEL_USER = "mannschaft:ws:relay:user";

    /** 再注入メッセージに付与するリレーマーカーヘッダ（§4.4 / §4.5.2）。 */
    public static final String RELAY_MARKER_HEADER = "X-Relay-Injected";

    /** {@link RelayEnvelope#getMessageType()} = ブロードキャスト（§4.5.1）。 */
    public static final String MESSAGE_TYPE_BROADCAST = "BROADCAST";

    /** {@link RelayEnvelope#getMessageType()} = ユーザー宛（§4.5.1）。 */
    public static final String MESSAGE_TYPE_USER = "USER";
}
