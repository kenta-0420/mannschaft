package com.mannschaft.app.websocket.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * Valkey チャネルを購読し、受信 {@link RelayEnvelope} を自ノードの SimpleBroker に再注入する
 * （設計書 §4.1 / §4.2 / §4.3 / §4.4 / §4.5.2）。
 *
 * <p><b>スケルトン（本ファイルはロジック未実装）</b>: originNodeId 一致の破棄（ループ防止・§4.4）・
 * BROADCAST の brokerChannel 直接再注入（リレーマーカーヘッダ付与・§4.5.2）・USER の
 * {@code convertAndSendToUser} ヘッダ付き再実行（§4.3）は<b>出陣隊（隊 1）が実装する</b>。</p>
 */
public class WebSocketRelaySubscriber implements MessageListener {

    private final String nodeId;
    private final ObjectMapper objectMapper;
    private final MessageChannel brokerChannel;
    private final SimpMessagingTemplate messagingTemplate;
    private final MeterRegistry meterRegistry;

    public WebSocketRelaySubscriber(String nodeId,
                                    ObjectMapper objectMapper,
                                    MessageChannel brokerChannel,
                                    SimpMessagingTemplate messagingTemplate,
                                    MeterRegistry meterRegistry) {
        this.nodeId = nodeId;
        this.objectMapper = objectMapper;
        this.brokerChannel = brokerChannel;
        this.messagingTemplate = messagingTemplate;
        this.meterRegistry = meterRegistry;
    }

    /** 自ノード ID（受信封筒の originNodeId と突合してループを防ぐ・§4.4）。 */
    public String getNodeId() {
        return nodeId;
    }

    /**
     * Valkey から中継メッセージを受信し、自ノード発でなければ SimpleBroker へ再注入する（§4.2 / §4.3）。
     *
     * <p>スケルトンは no-op。出陣隊が originNodeId 破棄・destination 別再注入・マーカー付与を実装する。</p>
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        // 出陣で実装（§4.4 originNodeId 破棄・§4.5.2 再注入ヘッダ・§4.3 USER 再実行）
    }
}
