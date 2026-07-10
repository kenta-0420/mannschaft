package com.mannschaft.app.websocket.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * §7.1.3 手組み結合テスト — Valkey 断フェイルオープン（AC-4）を Testcontainers の pause/unpause で検証する。
 *
 * <p>断の再現は container の {@code pause}/{@code unpause}（{@code stop()}→{@code start()} は mapped port が変わり別問題を見てしまうため使わない・§7.1.3）。</p>
 *
 * <h3>アサート構成（§7.1.3）</h3>
 * <ul>
 *   <li><b>ガード（green）</b>: pause 中の {@code publisher.relay(...)} が<b>例外を投げない</b>（フェイルオープン。skeleton no-op でも成立し、出陣後も握り潰さず warn で継続）。</li>
 *   <li><b>red 駆動 (1)</b>: pause 中の publish 失敗が {@code relay.publish.failure} に計上される（skeleton は計上しない → red）。</li>
 *   <li><b>red 駆動 (2)</b>: unpause 後に publish→受信の中継が<b>復帰</b>する（subscriber が再注入する。skeleton no-op → red）。</li>
 * </ul>
 */
@DisplayName("§7.1.3 relay フェイルオープン 手組み結合（AC-4 Valkey 断 pause/unpause）")
@EnabledIf("com.mannschaft.app.websocket.relay.WebSocketRelayFailOpenIntegrationTest#isDockerAvailable")
class WebSocketRelayFailOpenIntegrationTest {

    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7-alpine");

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS = new GenericContainer<>(REDIS_IMAGE)
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(120)));

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
        // 念のため（テスト途中 abort 時など）unpause してから破棄する
        ensureUnpaused();
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (REDIS.isRunning()) {
            REDIS.stop();
        }
    }

    @AfterEach
    void cleanup() {
        for (RedisMessageListenerContainer c : startedContainers) {
            c.stop();
        }
        startedContainers.clear();
        ensureUnpaused();
        if (connectionFactory != null) {
            connectionFactory.getConnection().serverCommands().flushAll();
        }
    }

    @Test
    @DisplayName("AC-4 ガード: Valkey 断（pause）中の publish は例外を投げない（フェイルオープン）")
    void publishDuringPause_doesNotThrow() {
        WebSocketRelayPublisher publisherA = newPublisher(NODE_A, new SimpleMeterRegistry());
        pause();
        assertThatCode(() -> publisherA.relay(brokerMessage("/topic/channels/42", "{}")))
                .as("Valkey 断中でも publish は例外を投げないこと（症状は warn で残すが配信経路は巻き戻さない・§8.1）")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC-4 red: Valkey 断（pause）中の publish 失敗が relay.publish.failure に計上される")
    void publishFailureDuringPause_isCounted() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        WebSocketRelayPublisher publisherA = newPublisher(NODE_A, meterRegistry);

        pause();
        publisherA.relay(brokerMessage("/topic/channels/42", "{}"));

        Counter failure = meterRegistry.find("relay.publish.failure").counter();
        assertThat(failure)
                .as("フェイルオープンでも失敗は沈黙させずメトリクスに計上すること（§8.4・skeleton 未計上 → red）")
                .isNotNull();
        assertThat(failure.count())
                .as("relay.publish.failure が 1 以上であること")
                .isGreaterThanOrEqualTo(1.0d);
    }

    @Test
    @DisplayName("AC-4 red: unpause 後に publish→受信の中継が自動復帰する")
    void relayResumesAfterUnpause() throws Exception {
        MessageChannel brokerChannelB = mock(MessageChannel.class);
        registerSubscriber(NODE_B, brokerChannelB, WebSocketRelayConstants.CHANNEL_BROADCAST);
        WebSocketRelayPublisher publisherA = newPublisher(NODE_A, new SimpleMeterRegistry());

        pause();
        publisherA.relay(brokerMessage("/topic/channels/42", "{}")); // 断中は届かない
        unpause();

        // 復旧後の publish は中継され、ノード B が再注入する
        publisherA.relay(brokerMessage("/topic/channels/42", "{\"text\":\"復帰\"}"));

        verify(brokerChannelB, timeout(4000))
                .send(org.mockito.ArgumentMatchers.any());
    }

    // ───────────────────────── ヘルパ ─────────────────────────

    private WebSocketRelayPublisher newPublisher(String nodeId, SimpleMeterRegistry meterRegistry) {
        return new WebSocketRelayPublisher(redisTemplate, objectMapper, nodeId, meterRegistry);
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

    private Message<byte[]> brokerMessage(String destination, String jsonBody) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setDestination(destination);
        accessor.setContentType(MimeTypeUtils.APPLICATION_JSON);
        return MessageBuilder.createMessage(jsonBody.getBytes(StandardCharsets.UTF_8), accessor.getMessageHeaders());
    }

    private void pause() {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
    }

    private void unpause() {
        REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
    }

    private static void ensureUnpaused() {
        try {
            if (REDIS.getContainerId() != null
                    && Boolean.TRUE.equals(REDIS.getDockerClient()
                    .inspectContainerCmd(REDIS.getContainerId()).exec().getState().getPaused())) {
                REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
            }
        } catch (Exception ignored) {
            // best-effort（テスト後始末）
        }
    }
}
