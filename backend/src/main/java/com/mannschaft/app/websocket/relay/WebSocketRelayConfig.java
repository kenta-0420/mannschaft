package com.mannschaft.app.websocket.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.websocket.WebSocketNodeIdProvider;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.AbstractMessageChannel;

import java.util.List;

/**
 * WebSocket 中継（relay）部品の DI 構成（設計書 §1.3 / §4.1 / §4.2.1）。
 *
 * <p>relay 部品（{@link WebSocketRelayPublisher} / {@link WebSocketRelaySubscriber} /
 * relay 用 {@link RedisMessageListenerContainer}）は
 * {@code @ConditionalOnProperty(mannschaft.websocket.relay.enabled)} で <b>flag OFF 時は Bean 不生成</b>
 * （現行 SimpleBroker と完全同一挙動・§1.3・AC-3）。</p>
 *
 * <p>ノード ID（起動時生成 UUID・{@link WebSocketNodeIdProvider}）と {@code /actuator/info} への
 * node-id 公開（§7.4.2）は relay flag に<b>依存しない無条件 Bean</b>とする
 * （CONNECT ログ・実機 E2E の別ノード裏取りは flag OFF でも有用なため）。</p>
 *
 * <h3>Publisher の brokerChannel 登録（§4.2.1 Y-2）</h3>
 * <p>brokerChannel への interceptor 登録に {@code @Order} は効かない（brokerChannel 用の configure
 * コールバックが存在しない）ため、{@link SmartInitializingSingleton} の初期化フックで
 * {@code AbstractMessageChannel#addInterceptor} を明示呼び出しして登録する（挿入順=既存登録の後尾=最終段）。</p>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(WebSocketRelayProperties.class)
public class WebSocketRelayConfig {

    private static final String RELAY_PROPERTY_PREFIX = "mannschaft.websocket.relay";

    /** ノード ID（起動時生成 UUID・§4.4 / §7.4.2）。relay flag 非依存の無条件 Bean。 */
    @Bean
    public WebSocketNodeIdProvider webSocketNodeIdProvider() {
        return new WebSocketNodeIdProvider();
    }

    /** {@code /actuator/info} に nodeId を公開する（§7.4.2・AC-2/AC-9 の別ノード裏取り観測手段）。 */
    @Bean
    public InfoContributor webSocketNodeIdInfoContributor(WebSocketNodeIdProvider nodeIdProvider) {
        return builder -> builder.withDetail("nodeId", nodeIdProvider.getNodeId());
    }

    @Bean
    @ConditionalOnProperty(prefix = RELAY_PROPERTY_PREFIX, name = "enabled", havingValue = "true")
    public WebSocketRelayPublisher webSocketRelayPublisher(StringRedisTemplate stringRedisTemplate,
                                                           ObjectMapper objectMapper,
                                                           WebSocketNodeIdProvider nodeIdProvider,
                                                           MeterRegistry meterRegistry) {
        return new WebSocketRelayPublisher(
                stringRedisTemplate, objectMapper, nodeIdProvider.getNodeId(), meterRegistry);
    }

    @Bean
    @ConditionalOnProperty(prefix = RELAY_PROPERTY_PREFIX, name = "enabled", havingValue = "true")
    public WebSocketRelaySubscriber webSocketRelaySubscriber(WebSocketNodeIdProvider nodeIdProvider,
                                                             ObjectMapper objectMapper,
                                                             @Qualifier("brokerChannel") MessageChannel brokerChannel,
                                                             SimpMessagingTemplate messagingTemplate,
                                                             MeterRegistry meterRegistry) {
        return new WebSocketRelaySubscriber(
                nodeIdProvider.getNodeId(), objectMapper, brokerChannel, messagingTemplate, meterRegistry);
    }

    /**
     * Publisher を brokerChannel の {@code ChannelInterceptor} として登録する（§4.2.1 Y-2）。
     *
     * <p>全シングルトン初期化後（{@code afterSingletonsInstantiated}）に {@code addInterceptor} を
     * 明示呼び出しする（既存インターセプタの後尾に追加＝最終段。アノテーション順序に依存しない）。</p>
     */
    @Bean
    @ConditionalOnProperty(prefix = RELAY_PROPERTY_PREFIX, name = "enabled", havingValue = "true")
    public SmartInitializingSingleton webSocketRelayPublisherRegistration(
            @Qualifier("brokerChannel") MessageChannel brokerChannel,
            WebSocketRelayPublisher publisher) {
        return () -> {
            if (brokerChannel instanceof AbstractMessageChannel abstractChannel) {
                abstractChannel.addInterceptor(publisher);
                log.info("WebSocket relay publisher を brokerChannel に登録しました: nodeId={}", publisher.getNodeId());
            } else {
                // 実アプリの brokerChannel は AbstractSubscribableChannel（AbstractMessageChannel 派生）。
                // ここに来るのはテストのモック等のみ（登録不能を沈黙させず可視化する）。
                log.warn("brokerChannel が AbstractMessageChannel ではないため relay publisher を登録できません: type={}",
                        brokerChannel.getClass().getName());
            }
        };
    }

    /**
     * relay 用の Valkey 購読コンテナ（Lettuce 購読専用接続・§2.4 / §4.1）。
     * broadcast / user の 2 チャネル（§4.2 固定）に {@link WebSocketRelaySubscriber} を登録する。
     *
     * <p>接続喪失時は Lettuce の再接続により購読が自動復旧する（フェイルオープン・§8.1・AC-4）。</p>
     */
    @Bean
    @ConditionalOnProperty(prefix = RELAY_PROPERTY_PREFIX, name = "enabled", havingValue = "true")
    public RedisMessageListenerContainer webSocketRelayListenerContainer(
            ObjectProvider<RedisConnectionFactory> connectionFactoryProvider,
            StringRedisTemplate stringRedisTemplate,
            WebSocketRelaySubscriber subscriber) {
        RedisConnectionFactory connectionFactory = connectionFactoryProvider.getIfAvailable();
        if (connectionFactory == null) {
            connectionFactory = stringRedisTemplate.getConnectionFactory();
        }
        if (connectionFactory == null) {
            // RedisConnectionFactory が存在しない構成（軽量テストコンテキスト等）では購読コンテナを生成しない
            // （実アプリでは spring-boot-starter-data-redis の自動構成により必ず存在する）。
            log.warn("RedisConnectionFactory が存在しないため relay 購読コンテナを生成しません（中継の受信は無効）");
            return null;
        }
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, List.of(
                new ChannelTopic(WebSocketRelayConstants.CHANNEL_BROADCAST),
                new ChannelTopic(WebSocketRelayConstants.CHANNEL_USER)));
        return container;
    }
}
