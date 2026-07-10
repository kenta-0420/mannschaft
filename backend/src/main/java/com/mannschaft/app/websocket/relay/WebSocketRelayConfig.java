package com.mannschaft.app.websocket.relay;

import org.springframework.context.annotation.Configuration;

/**
 * WebSocket 中継（relay）部品の DI 構成（設計書 §1.3 / §4.1）。
 *
 * <p><b>スケルトン（Bean 宣言なし）</b>: 出陣隊（隊 1）が以下を
 * {@code @ConditionalOnProperty(prefix="mannschaft.websocket.relay", name="enabled", havingValue="true")}
 * 付きの {@code @Bean} として本クラスに追加する（flag OFF 時は Bean 不生成 = 現行と完全同一挙動・§1.3・AC-3）:</p>
 * <ul>
 *   <li>{@link WebSocketRelayPublisher}（自ノード起動時 UUID を nodeId として付与）</li>
 *   <li>{@link WebSocketRelaySubscriber}</li>
 *   <li>relay 用 {@code RedisMessageListenerContainer}（Lettuce 購読専用接続・subscriber を登録）</li>
 * </ul>
 *
 * <p>現時点で Bean を一切宣言していないため、{@code enabled=true} でも relay Bean は生成されない。
 * これは AC-3 の red テスト（{@code WebSocketRelayBeanRegistrationTest}）が「ON 時に Bean が存在すること」を
 * 期待して失敗する状態であり、出陣で Bean を宣言すると green 化する。</p>
 */
@Configuration
public class WebSocketRelayConfig {
    // 出陣で @ConditionalOnProperty 付き @Bean（Publisher / Subscriber / RedisMessageListenerContainer）を追加する
}
