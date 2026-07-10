package com.mannschaft.app.websocket.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * 自ノードの brokerChannel に流れたメッセージを捕捉し、{@link RelayEnvelope} にラップして
 * Valkey チャネルへ publish する（設計書 §4.1 / §4.2 / §4.2.1 / §4.4）。
 *
 * <p>brokerChannel の {@link ChannelInterceptor}（{@code preSend}・メッセージ非改変）として動作する。
 * 登録は {@code WebSocketRelayConfig} が初期化フックで {@code AbstractMessageChannel#addInterceptor}
 * を明示呼び出しして行う（{@code @Order} 不可・挿入順=最終段・§4.2.1 Y-2）。</p>
 *
 * <h3>捕捉規則（§4.2.1・固定）</h3>
 * <ul>
 *   <li>(i) {@code /topic/} 始まり → BROADCAST 中継（{@code mannschaft:ws:relay:broadcast}）</li>
 *   <li>(ii) {@code /user/} 始まり（未解決ユーザー宛）→ USER 中継（{@code mannschaft:ws:relay:user}・
 *       userId とサブ destination に分解して封筒化・§4.3）</li>
 *   <li>(iii) それ以外（解決済み {@code /queue/...-user*} を含む）→ 中継しない</li>
 *   <li>リレーマーカー（{@code X-Relay-Injected}）付きメッセージは再 publish しない（再捕捉ループ防止・§4.4）</li>
 * </ul>
 *
 * <h3>フェイルオープン（§8.1 / §8.4）</h3>
 * <p>publish 失敗（Valkey 断等）は例外を上げず warn ログ＋{@code relay.publish.failure} 計上のみ
 * （配信経路を巻き戻さない。ローカル SimpleBroker 配信は継続）。</p>
 */
@Slf4j
public class WebSocketRelayPublisher implements ChannelInterceptor {

    private static final String TOPIC_PREFIX = "/topic/";
    private static final String USER_PREFIX = "/user/";
    private static final String DEFAULT_CONTENT_TYPE = "application/json";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String nodeId;

    private final Counter publishBroadcastCount;
    private final Counter publishUserCount;
    private final Counter publishBroadcastFailure;
    private final Counter publishUserFailure;
    private final Timer valkeyRttTimer;

    public WebSocketRelayPublisher(StringRedisTemplate redisTemplate,
                                   ObjectMapper objectMapper,
                                   String nodeId,
                                   MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.nodeId = nodeId;
        this.publishBroadcastCount = counter(meterRegistry, "relay.publish.count", "broadcast");
        this.publishUserCount = counter(meterRegistry, "relay.publish.count", "user");
        this.publishBroadcastFailure = counter(meterRegistry, "relay.publish.failure", "broadcast");
        this.publishUserFailure = counter(meterRegistry, "relay.publish.failure", "user");
        this.valkeyRttTimer = Timer.builder("relay.valkey.rtt")
                .tag("nodeId", nodeId)
                .register(meterRegistry);
    }

    private Counter counter(MeterRegistry meterRegistry, String name, String destinationType) {
        return Counter.builder(name)
                .tag("nodeId", nodeId)
                .tag("destinationType", destinationType)
                .register(meterRegistry);
    }

    /** 発信ノードの一意 ID（起動時生成 UUID・ループ防止・§4.4）。 */
    public String getNodeId() {
        return nodeId;
    }

    /**
     * brokerChannel の preSend で捕捉する（メッセージは一切改変せずそのまま返す・§4.2.1）。
     */
    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        relay(message);
        return message;
    }

    /**
     * brokerChannel を通過したアウトバウンドメッセージを中継対象なら Valkey へ publish する（§4.2.1）。
     *
     * <p>いかなる場合も例外を呼び出し元（=配信経路）へ伝播させない（フェイルオープン・§8.1）。</p>
     *
     * @param brokerMessage brokerChannel の {@code preSend} で受け取るメッセージ
     */
    public void relay(Message<?> brokerMessage) {
        try {
            MessageHeaders headers = brokerMessage.getHeaders();
            if (Boolean.TRUE.equals(headers.get(WebSocketRelayConstants.RELAY_MARKER_HEADER))) {
                // 再注入済みメッセージは再 publish しない（三重防御の 2 段目・§4.4）
                return;
            }
            String destination = SimpMessageHeaderAccessor.getDestination(headers);
            if (destination == null) {
                return;
            }
            if (destination.startsWith(TOPIC_PREFIX)) {
                // (i) broadcast（§4.2.1）
                publish(WebSocketRelayConstants.CHANNEL_BROADCAST,
                        WebSocketRelayConstants.MESSAGE_TYPE_BROADCAST,
                        destination, null, brokerMessage,
                        publishBroadcastCount, publishBroadcastFailure);
            } else if (destination.startsWith(USER_PREFIX)) {
                // (ii) 未解決ユーザー宛: /user/{userId}{subDestination} を分解して論理宛先を中継（§4.3）
                int subStart = destination.indexOf('/', USER_PREFIX.length());
                if (subStart < 0) {
                    return;
                }
                String userId = destination.substring(USER_PREFIX.length(), subStart);
                String subDestination = destination.substring(subStart);
                publish(WebSocketRelayConstants.CHANNEL_USER,
                        WebSocketRelayConstants.MESSAGE_TYPE_USER,
                        subDestination, userId, brokerMessage,
                        publishUserCount, publishUserFailure);
            }
            // (iii) 上記以外（解決済み /queue/...-user* 等）は中継しない（§4.2.1）
        } catch (RuntimeException e) {
            // 捕捉ロジック自体の予期しない失敗も配信経路へは伝播させない（症状は warn で可視化・§8.1）
            log.warn("relay捕捉処理で予期しない例外（フェイルオープン・ローカル配信は継続）", e);
        }
    }

    private void publish(String channel, String messageType, String envelopeDestination, String userId,
                         Message<?> brokerMessage, Counter successCounter, Counter failureCounter) {
        try {
            RelayEnvelope envelope = new RelayEnvelope(
                    nodeId,
                    messageType,
                    envelopeDestination,
                    userId,
                    contentType(brokerMessage.getHeaders()),
                    Base64.getEncoder().encodeToString(payloadBytes(brokerMessage)));
            byte[] rawChannel = channel.getBytes(StandardCharsets.UTF_8);
            byte[] rawEnvelope = objectMapper.writeValueAsBytes(envelope);
            redisTemplate.execute((RedisCallback<Void>) connection -> {
                // 接続ヘルスプローブ（PING）を publish 前に打つ。Valkey 断（停止/フリーズ）中は
                // ここでコマンドタイムアウトし、PUBLISH コマンド自体をソケットへ書き込まない
                // （断中に書き込まれた PUBLISH が復旧後にサーバー側で遅延実行され、
                // 「断中の配信が復旧後に化けて届く」stale 配信になるのを防ぐ）。
                // レイテンシは §8.4 の relay.valkey.rtt（Timer）として計測する。
                long pingStart = System.nanoTime();
                connection.ping();
                valkeyRttTimer.record(System.nanoTime() - pingStart, TimeUnit.NANOSECONDS);
                connection.publish(rawChannel, rawEnvelope);
                return null;
            });
            successCounter.increment();
        } catch (Exception e) {
            failureCounter.increment();
            log.warn("relay publish失敗（フェイルオープン・ローカル配信は継続・§8.1）: channel={}, destination={}",
                    channel, envelopeDestination, e);
        }
    }

    private String contentType(MessageHeaders headers) {
        Object contentType = headers.get(MessageHeaders.CONTENT_TYPE);
        return contentType != null ? contentType.toString() : DEFAULT_CONTENT_TYPE;
    }

    private byte[] payloadBytes(Message<?> brokerMessage) throws Exception {
        Object payload = brokerMessage.getPayload();
        if (payload instanceof byte[] bytes) {
            return bytes;
        }
        if (payload instanceof String text) {
            return text.getBytes(StandardCharsets.UTF_8);
        }
        // brokerChannel のメッセージは通常 byte[]（コンバータ変換済み）。防御的フォールバック。
        return objectMapper.writeValueAsBytes(payload);
    }
}
