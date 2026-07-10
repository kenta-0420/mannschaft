package com.mannschaft.app.websocket.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;

/**
 * 自ノードの brokerChannel に流れたメッセージを捕捉し、{@link RelayEnvelope} にラップして
 * Valkey チャネルへ publish する（設計書 §4.1 / §4.2 / §4.2.1 / §4.4）。
 *
 * <p><b>スケルトン（本ファイルはロジック未実装）</b>: 捕捉規則（§4.2.1 — {@code /topic/} は BROADCAST、
 * 未解決 {@code /user/} は USER、解決済み {@code /queue/...-user*} は中継対象外）・リレーマーカー付き
 * メッセージの publish 抑止（§4.4）・封筒シリアライズ・publish 失敗のフェイルオープン計上（§8.1/§8.4）は
 * <b>出陣隊（隊 1）が実装する</b>。試練（本 red テスト）はこれらの未実装を実証するために先行して書かれている。</p>
 */
public class WebSocketRelayPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String nodeId;
    private final MeterRegistry meterRegistry;

    public WebSocketRelayPublisher(StringRedisTemplate redisTemplate,
                                   ObjectMapper objectMapper,
                                   String nodeId,
                                   MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.nodeId = nodeId;
        this.meterRegistry = meterRegistry;
    }

    /** 発信ノードの一意 ID（起動時生成 UUID・ループ防止・§4.4）。 */
    public String getNodeId() {
        return nodeId;
    }

    /**
     * brokerChannel を通過したアウトバウンドメッセージを中継対象なら Valkey へ publish する（§4.2.1）。
     *
     * <p>スケルトンは no-op。出陣隊が捕捉規則・封筒化・publish・フェイルオープンを実装する。</p>
     *
     * @param brokerMessage brokerChannel の {@code preSend} で受け取るメッセージ
     */
    public void relay(Message<?> brokerMessage) {
        // 出陣で実装（§4.2.1 捕捉規則・§4.4 ループ防止・§8.1 フェイルオープン）
    }
}
