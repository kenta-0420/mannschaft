package com.mannschaft.app.websocket;

import java.util.UUID;

/**
 * WebSocket ノード識別子（設計書 §4.4 / §7.4.2）。
 *
 * <p>起動時に生成する UUID をノード ID として保持する（ECS タスク ID 案は廃止 — 環境非依存・§4.4）。
 * relay のループ防止（{@code RelayEnvelope.originNodeId}）・CONNECT ログ・{@code /actuator/info} の
 * node-id 観測（AC-2/AC-9 の「別ノード接続」裏取り・§7.4.2）で共通に使用するため、
 * ノード内で単一インスタンス（Bean）とする。Bean 宣言は {@code WebSocketRelayConfig}（無条件・relay flag 非依存）。</p>
 */
public final class WebSocketNodeIdProvider {

    private final String nodeId = UUID.randomUUID().toString();

    /** 起動時生成のノード一意 ID（UUID 文字列）。 */
    public String getNodeId() {
        return nodeId;
    }
}
