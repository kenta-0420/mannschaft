package com.mannschaft.app.websocket.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.MimeTypeUtils;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * §7.1.1 手組み結合テスト — broadcast 中継の中核部品（AC-1）とループ防止（AC-6）を実 Valkey で検証する。
 *
 * <p>{@code ValkeyRateLimiterIntegrationTest} の流儀を踏襲（{@code @SpringBootTest} 不使用 = {@code AbstractMySqlIntegrationTest}
 * との TestContext キャッシュ分裂回避）。{@code redis:7-alpine} を Testcontainers で起動し、relay 部品（Publisher / Subscriber）を
 * <b>別 nodeId で 2 セット手組み</b>して「ノード A 発 publish → Valkey → ノード B 受信 → 再注入呼び出し」を検証する。
 * SimpleBroker 本体はモック（{@link MessageChannel} brokerChannel）とし、<b>再注入の呼び出し内容（destination・リレーマーカー）</b>まで確認する。</p>
 *
 * <h3>red 駆動（skeleton は Publisher.relay / Subscriber.onMessage が no-op）</h3>
 * <ul>
 *   <li><b>AC-1 publisher</b>: {@code publisherA.relay(broadcast)} → Valkey チャネルに封筒が publish される（skeleton 未実装 → red）。</li>
 *   <li><b>AC-1 subscriber</b>: Valkey で受信した cross-origin 封筒 → brokerChannel へ再注入（marker 付き・skeleton 未実装 → red）。</li>
 *   <li><b>AC-6 own-origin drop</b>: 自ノード発封筒は再注入しない（companion。skeleton でも「送らない」は満たすが、出陣後に意味を持つ）。</li>
 *   <li><b>AC-6 marker 抑止 / 解決済み user 無視</b>: マーカー付き・解決済み {@code /queue/...-user*} は publish しない（companion）。</li>
 * </ul>
 */
@DisplayName("§7.1.1 relay 中核 手組み結合（AC-1 broadcast 到達 / AC-6 ループ防止）")
@EnabledIf("com.mannschaft.app.websocket.relay.WebSocketRelayCoreIntegrationTest#isDockerAvailable")
class WebSocketRelayCoreIntegrationTest {

    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7-alpine");

    // 環境注記: Wait.forListeningPort() は docker exec 経由の内部ポート確認を伴うが、
    // 開発機の Docker TCP プロキシ環境では exec のストリームハイジャックが正しく中継されず
    // ContainerLaunchException（内部チェックのみタイムアウト）が発生する（外部からの TCP 到達性は問題ない）。
    // ログメッセージ待機（docker logs 経由・exec 不要）に切り替えて回避する（CI の素の Docker でも問題なく動作）。
    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS = new GenericContainer<>(REDIS_IMAGE)
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections tcp.*\\n", 1)
                    .withStartupTimeout(Duration.ofSeconds(120)));

    private static final String NODE_A = "node-A";
    private static final String NODE_B = "node-B";

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<RedisMessageListenerContainer> startedContainers = new ArrayList<>();

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeAll
    static void startContainer() {
        if (!isDockerAvailable()) {
            return;
        }
        try {
            REDIS.start();
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.abort("Redisコンテナ起動失敗（環境問題）: " + e.getMessage());
        }
        RedisStandaloneConfiguration standalone =
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getFirstMappedPort());
        connectionFactory = new LettuceConnectionFactory(standalone);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void stopContainer() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (REDIS.isRunning()) {
            REDIS.stop();
        }
    }

    @BeforeEach
    void flush() {
        if (connectionFactory != null) {
            connectionFactory.getConnection().serverCommands().flushAll();
        }
    }

    @AfterEach
    void stopListeners() {
        for (RedisMessageListenerContainer c : startedContainers) {
            c.stop();
        }
        startedContainers.clear();
    }

    // ───────────────────────── AC-1 ─────────────────────────

    @Test
    @DisplayName("AC-1: publisher がブロードキャストメッセージを Valkey チャネルへ封筒 publish する（red）")
    void publisher_publishesBroadcastEnvelopeToValkey() throws Exception {
        BlockingQueue<String> received = subscribeRaw(WebSocketRelayConstants.CHANNEL_BROADCAST);

        WebSocketRelayPublisher publisherA = newPublisher(NODE_A);
        Message<byte[]> broadcast = brokerMessage("/topic/channels/42", "{\"text\":\"こんにちは\"}");

        publisherA.relay(broadcast);

        String json = received.poll(3, TimeUnit.SECONDS);
        assertThat(json)
                .as("publisher.relay で /topic 宛メッセージが Valkey broadcast チャネルへ publish されること（出陣で実装）")
                .isNotNull();

        RelayEnvelope envelope = objectMapper.readValue(json, RelayEnvelope.class);
        assertThat(envelope.getMessageType()).isEqualTo(WebSocketRelayConstants.MESSAGE_TYPE_BROADCAST);
        assertThat(envelope.getDestination()).isEqualTo("/topic/channels/42");
        assertThat(envelope.getOriginNodeId()).isEqualTo(NODE_A);
    }

    @Test
    @DisplayName("AC-1: subscriber が他ノード発の封筒を SimpleBroker へリレーマーカー付きで再注入する（red）")
    void subscriber_reinjectsCrossOriginEnvelope() throws Exception {
        MessageChannel brokerChannelB = mock(MessageChannel.class);
        registerSubscriber(NODE_B, brokerChannelB, WebSocketRelayConstants.CHANNEL_BROADCAST);

        // ノード A 発の封筒を Valkey へ流す（ノード B から見れば cross-origin）
        String envelopeJson = objectMapper.writeValueAsString(new RelayEnvelope(
                NODE_A,
                WebSocketRelayConstants.MESSAGE_TYPE_BROADCAST,
                "/topic/channels/42",
                null,
                MimeTypeUtils.APPLICATION_JSON_VALUE,
                java.util.Base64.getEncoder().encodeToString("{\"text\":\"hi\"}".getBytes(StandardCharsets.UTF_8))));
        redisTemplate.convertAndSend(WebSocketRelayConstants.CHANNEL_BROADCAST, envelopeJson);

        ArgumentCaptor<Message<?>> captor = messageCaptor();
        verify(brokerChannelB, timeout(3000))
                .send(captor.capture());

        Message<?> reinjected = captor.getValue();
        assertThat(reinjected.getHeaders().get(SimpMessageHeaderAccessor.DESTINATION_HEADER))
                .as("再注入メッセージの destination が封筒どおりであること（§4.5.2）")
                .isEqualTo("/topic/channels/42");
        assertThat(reinjected.getHeaders().get(WebSocketRelayConstants.RELAY_MARKER_HEADER))
                .as("再注入メッセージにリレーマーカーが付与されること（§4.4 / §4.5.2）")
                .isEqualTo(Boolean.TRUE);
    }

    // ───────────────────────── AC-6 ─────────────────────────

    @Test
    @DisplayName("AC-6: subscriber は自ノード発（originNodeId 一致）の封筒を再注入しない（companion）")
    void subscriber_dropsOwnOriginEnvelope() throws Exception {
        MessageChannel brokerChannelB = mock(MessageChannel.class);
        registerSubscriber(NODE_B, brokerChannelB, WebSocketRelayConstants.CHANNEL_BROADCAST);

        // ノード B 自身が発したものと同一 originNodeId の封筒 → 破棄されるべき（§4.4）
        String selfEnvelope = objectMapper.writeValueAsString(new RelayEnvelope(
                NODE_B,
                WebSocketRelayConstants.MESSAGE_TYPE_BROADCAST,
                "/topic/channels/42",
                null,
                MimeTypeUtils.APPLICATION_JSON_VALUE,
                java.util.Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8))));
        redisTemplate.convertAndSend(WebSocketRelayConstants.CHANNEL_BROADCAST, selfEnvelope);

        verify(brokerChannelB, after(1500).never())
                .send(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("AC-6: publisher はリレーマーカー付きメッセージを publish しない（再捕捉ループ防止・companion）")
    void publisher_doesNotRelayMarkedMessage() throws Exception {
        BlockingQueue<String> received = subscribeRaw(WebSocketRelayConstants.CHANNEL_BROADCAST);

        WebSocketRelayPublisher publisherB = newPublisher(NODE_B);
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setDestination("/topic/channels/42");
        accessor.setHeader(WebSocketRelayConstants.RELAY_MARKER_HEADER, Boolean.TRUE);
        Message<byte[]> marked = MessageBuilder.createMessage("{}".getBytes(StandardCharsets.UTF_8),
                accessor.getMessageHeaders());

        publisherB.relay(marked);

        assertThat(received.poll(1500, TimeUnit.MILLISECONDS))
                .as("リレーマーカー付きメッセージは再 publish されないこと（§4.4）")
                .isNull();
    }

    @Test
    @DisplayName("AC-6: publisher は解決済みユーザー宛（/queue/...-user*）を中継しない（§4.2.1(iii)・companion）")
    void publisher_ignoresResolvedUserDestination() throws Exception {
        BlockingQueue<String> received = subscribeRaw(WebSocketRelayConstants.CHANNEL_BROADCAST);
        BlockingQueue<String> receivedUser = subscribeRaw(WebSocketRelayConstants.CHANNEL_USER);

        WebSocketRelayPublisher publisherB = newPublisher(NODE_B);
        Message<byte[]> resolved = brokerMessage("/queue/notifications-user123", "{}");

        publisherB.relay(resolved);

        assertThat(received.poll(1000, TimeUnit.MILLISECONDS)).as("broadcast チャネルへ流れないこと").isNull();
        assertThat(receivedUser.poll(1000, TimeUnit.MILLISECONDS)).as("user チャネルへも流れないこと（解決済みは中継対象外）").isNull();
    }

    // ───────────────────────── ヘルパ ─────────────────────────

    private WebSocketRelayPublisher newPublisher(String nodeId) {
        return new WebSocketRelayPublisher(redisTemplate, objectMapper, nodeId, new SimpleMeterRegistry());
    }

    private void registerSubscriber(String nodeId, MessageChannel brokerChannel, String channel) {
        WebSocketRelaySubscriber subscriber = new WebSocketRelaySubscriber(
                nodeId, objectMapper, brokerChannel, mock(SimpMessagingTemplate.class), new SimpleMeterRegistry());
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.afterPropertiesSet();
        container.addMessageListener(subscriber, new ChannelTopic(channel));
        container.start();
        startedContainers.add(container);
    }

    /** raw MessageListener を張り、受信 JSON 本文をキューに積む。 */
    private BlockingQueue<String> subscribeRaw(String channel) {
        BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.afterPropertiesSet();
        container.addMessageListener(
                (message, pattern) -> queue.offer(new String(message.getBody(), StandardCharsets.UTF_8)),
                new ChannelTopic(channel));
        container.start();
        startedContainers.add(container);
        return queue;
    }

    private Message<byte[]> brokerMessage(String destination, String jsonBody) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setDestination(destination);
        accessor.setContentType(MimeTypeUtils.APPLICATION_JSON);
        return MessageBuilder.createMessage(jsonBody.getBytes(StandardCharsets.UTF_8), accessor.getMessageHeaders());
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Message<?>> messageCaptor() {
        return ArgumentCaptor.forClass((Class<Message<?>>) (Class<?>) Message.class);
    }
}
