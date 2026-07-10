package com.mannschaft.app.websocket.relay;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.lang.NonNull;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.MimeType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * Valkey チャネルを購読し、受信 {@link RelayEnvelope} を自ノードの SimpleBroker に再注入する
 * （設計書 §4.1 / §4.2 / §4.3 / §4.4 / §4.5.2）。
 *
 * <h3>再注入の二経路（§4.3 / §4.4 / §4.5.2）</h3>
 * <ul>
 *   <li><b>BROADCAST</b>: brokerChannel へ {@code SimpMessageType.MESSAGE} の再注入メッセージを直接送出する。
 *       ヘッダは §4.5.2 の完全列挙（destination・contentType・リレーマーカー {@code X-Relay-Injected=true}）。</li>
 *   <li><b>USER</b>: {@code convertAndSendToUser(userId, subDestination, payload, headersWithRelayMarker)} の
 *       <b>ヘッダ付きオーバーロード</b>で再実行する（素の再実行はマーカーが載らず再捕捉ループ・§4.3）。
 *       受信ノードの {@code SimpUserRegistry}（そのノード接続中セッション）で解決される。</li>
 * </ul>
 *
 * <h3>ループ防止（§4.4 三重防御の 1 段目）</h3>
 * <p>封筒の {@code originNodeId} が自ノード ID と一致する場合は破棄する
 * （{@code relay.receive.dropped} 計上・§8.4）。</p>
 */
@Slf4j
public class WebSocketRelaySubscriber implements MessageListener {

    private final String nodeId;
    private final ObjectMapper objectMapper;
    private final ObjectReader envelopeReader;
    private final MessageChannel brokerChannel;
    private final SimpMessagingTemplate messagingTemplate;

    private final Counter receiveBroadcastCount;
    private final Counter receiveUserCount;
    private final Counter receiveDropped;
    private final Counter reinjectBroadcastCount;
    private final Counter reinjectUserCount;
    private final Counter reinjectBroadcastFailure;
    private final Counter reinjectUserFailure;

    public WebSocketRelaySubscriber(String nodeId,
                                    ObjectMapper objectMapper,
                                    MessageChannel brokerChannel,
                                    SimpMessagingTemplate messagingTemplate,
                                    MeterRegistry meterRegistry) {
        this.nodeId = nodeId;
        this.objectMapper = objectMapper;
        // §4.5.1: 封筒の前方互換 — 未知フィールドは無視する relay 専用リーダー
        this.envelopeReader = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .readerFor(RelayEnvelope.class);
        this.brokerChannel = brokerChannel;
        this.messagingTemplate = messagingTemplate;
        this.receiveBroadcastCount = counter(meterRegistry, "relay.receive.count", "broadcast");
        this.receiveUserCount = counter(meterRegistry, "relay.receive.count", "user");
        this.receiveDropped = Counter.builder("relay.receive.dropped")
                .tag("nodeId", nodeId)
                .register(meterRegistry);
        this.reinjectBroadcastCount = counter(meterRegistry, "relay.reinject.count", "broadcast");
        this.reinjectUserCount = counter(meterRegistry, "relay.reinject.count", "user");
        this.reinjectBroadcastFailure = counter(meterRegistry, "relay.reinject.failure", "broadcast");
        this.reinjectUserFailure = counter(meterRegistry, "relay.reinject.failure", "user");
    }

    private Counter counter(MeterRegistry meterRegistry, String name, String destinationType) {
        return Counter.builder(name)
                .tag("nodeId", nodeId)
                .tag("destinationType", destinationType)
                .register(meterRegistry);
    }

    /** 自ノード ID（受信封筒の originNodeId と突合してループを防ぐ・§4.4）。 */
    public String getNodeId() {
        return nodeId;
    }

    /**
     * Valkey から中継メッセージを受信し、自ノード発でなければ SimpleBroker へ再注入する（§4.2 / §4.3）。
     */
    @Override
    public void onMessage(@NonNull Message message, byte[] pattern) {
        RelayEnvelope envelope;
        try {
            envelope = envelopeReader.readValue(message.getBody());
        } catch (IOException e) {
            log.warn("relay封筒の解釈に失敗（当該メッセージのみ破棄・フェイルオープン）: channel={}",
                    new String(message.getChannel(), StandardCharsets.UTF_8), e);
            return;
        }

        boolean userType = WebSocketRelayConstants.MESSAGE_TYPE_USER.equals(envelope.getMessageType());
        (userType ? receiveUserCount : receiveBroadcastCount).increment();

        if (nodeId.equals(envelope.getOriginNodeId())) {
            // 自ノード発 → 破棄（ループ防止・§4.4。発信ノードではローカル SimpleBroker 経路で配信済み）
            receiveDropped.increment();
            return;
        }

        byte[] body = envelope.getBody() != null
                ? Base64.getDecoder().decode(envelope.getBody())
                : new byte[0];
        if (userType) {
            reinjectUser(envelope, body);
        } else {
            reinjectBroadcast(envelope, body);
        }
    }

    /** BROADCAST: brokerChannel へ §4.5.2 の完全列挙ヘッダで直接再注入する。 */
    private void reinjectBroadcast(RelayEnvelope envelope, byte[] body) {
        try {
            SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
            accessor.setDestination(envelope.getDestination());
            if (envelope.getContentType() != null) {
                accessor.setContentType(MimeType.valueOf(envelope.getContentType()));
            }
            accessor.setHeader(WebSocketRelayConstants.RELAY_MARKER_HEADER, Boolean.TRUE);
            accessor.setLeaveMutable(true);
            brokerChannel.send(MessageBuilder.createMessage(body, accessor.getMessageHeaders()));
            reinjectBroadcastCount.increment();
        } catch (RuntimeException e) {
            reinjectBroadcastFailure.increment();
            log.warn("relay broadcast再注入失敗（当該メッセージのみ破棄）: destination={}, originNodeId={}",
                    envelope.getDestination(), envelope.getOriginNodeId(), e);
        }
    }

    /**
     * USER: {@code convertAndSendToUser} のヘッダ付きオーバーロードで再実行する（§4.3 / §4.4）。
     * マーカーは (ii) 未解決メッセージの段階で Publisher に検知され publish が抑止される。
     */
    private void reinjectUser(RelayEnvelope envelope, byte[] body) {
        try {
            SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
            accessor.setHeader(WebSocketRelayConstants.RELAY_MARKER_HEADER, Boolean.TRUE);
            accessor.setLeaveMutable(true);
            messagingTemplate.convertAndSendToUser(
                    envelope.getUserId(),
                    envelope.getDestination(),
                    toPayload(envelope.getContentType(), body),
                    accessor.getMessageHeaders());
            reinjectUserCount.increment();
        } catch (Exception e) {
            reinjectUserFailure.increment();
            log.warn("relay user再注入失敗（当該メッセージのみ破棄）: userId={}, destination={}, originNodeId={}",
                    envelope.getUserId(), envelope.getDestination(), envelope.getOriginNodeId(), e);
        }
    }

    /**
     * USER 再実行のペイロード復元。JSON（§4.5.1 で実質固定）は Object へ復元して
     * ブローカーの Jackson コンバータに変換させる（byte[] のまま渡すと octet-stream 扱いになり
     * クライアント側の JSON コンバータが復元できないため）。非 JSON はバイト列のまま渡す。
     */
    private Object toPayload(String contentType, byte[] body) throws IOException {
        if (contentType == null || contentType.toLowerCase(Locale.ROOT).contains("json")) {
            return objectMapper.readValue(body, Object.class);
        }
        return body;
    }
}
