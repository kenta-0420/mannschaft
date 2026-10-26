package com.mannschaft.app.websocket.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * AC-3（非回帰）: relay flag OFF/ON による Bean 生成条件のテスト（設計書 §1.3 / §5 AC-3）。
 *
 * <p>Docker 不要（{@link ApplicationContextRunner} で {@code @ConditionalOnProperty} ロジックのみを軽量検証）。
 * {@code EmailTransportBeanSelectionTest} と同じ流儀。</p>
 *
 * <h3>red / green の設計根拠（重要・課題文の指示に対応）</h3>
 * <p>AC-3 は「flag OFF なら relay が無い」だけを見ると現行（relay 未実装）でも green になり条件を検査できない。
 * そこで本テストは<b>OFF と ON の両方</b>を検証し、<b>red の駆動アサートを「ON 時に relay Bean が生成されること」</b>に置く:</p>
 * <ul>
 *   <li><b>OFF（既定）→ relay Bean 不在</b>: 現行と完全同一挙動の非回帰ガード。skeleton でも green（{@link WebSocketRelayConfig} が
 *       Bean を宣言しないため不在）であり、出陣後も {@code @ConditionalOnProperty(havingValue="true")} により OFF では不在で green を維持する。</li>
 *   <li><b>ON → relay Bean 生成</b>: <b>これが red 駆動</b>。skeleton の {@link WebSocketRelayConfig} は Bean を一切宣言しないため、
 *       ON でも Publisher/Subscriber は生成されず「存在すること」の期待が<b>失敗（red）</b>する。出陣で条件付き {@code @Bean} を追加すると green 化する。</li>
 * </ul>
 * <p>すなわち本テストは「flag OFF の非回帰」と「flag ON で初めて relay が起動する」の両輪を、出陣後に意味を持つ形で固定する。</p>
 */
@DisplayName("AC-3: relay flag OFF/ON の Bean 生成条件（@ConditionalOnProperty）")
class WebSocketRelayBeanRegistrationTest {

    /**
     * relay 構成クラスと、将来 {@code @Bean} が依存する協調 Bean のモックを登録するベースランナー。
     * 協調 Bean は skeleton では未使用だが、出陣後に条件付き {@code @Bean} が配線できるよう予め供給しておく。
     */
    private final ApplicationContextRunner baseRunner = new ApplicationContextRunner()
            .withUserConfiguration(WebSocketRelayConfig.class)
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean("brokerChannel", MessageChannel.class, () -> mock(MessageChannel.class))
            .withBean(SimpMessagingTemplate.class, () -> mock(SimpMessagingTemplate.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Nested
    @DisplayName("flag OFF（既定・非回帰ガード）")
    class WhenDisabled {

        @Test
        @DisplayName("relay Publisher / Subscriber が生成されない（現行 SimpleBroker と完全同一挙動）")
        void relayBeans_absent_whenDisabled() {
            baseRunner
                    .withPropertyValues("mannschaft.websocket.relay.enabled=false")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context)
                                .as("flag OFF では WebSocketRelayPublisher は生成されないこと")
                                .doesNotHaveBean(WebSocketRelayPublisher.class);
                        assertThat(context)
                                .as("flag OFF では WebSocketRelaySubscriber は生成されないこと")
                                .doesNotHaveBean(WebSocketRelaySubscriber.class);
                    });
        }

        @Test
        @DisplayName("プロパティ未設定でも relay Bean は生成されない（安全側既定 OFF）")
        void relayBeans_absent_whenUnset() {
            baseRunner
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).doesNotHaveBean(WebSocketRelayPublisher.class);
                        assertThat(context).doesNotHaveBean(WebSocketRelaySubscriber.class);
                    });
        }
    }

    @Nested
    @DisplayName("flag ON（red 駆動: 出陣で green 化する）")
    class WhenEnabled {

        @Test
        @DisplayName("relay Publisher が生成される（skeleton は未宣言のため red）")
        void relayPublisher_present_whenEnabled() {
            baseRunner
                    .withPropertyValues("mannschaft.websocket.relay.enabled=true")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        // red 駆動: skeleton の WebSocketRelayConfig は Bean を宣言しないため存在しない → 失敗
                        assertThat(context)
                                .as("flag ON では WebSocketRelayPublisher が Bean として生成されること（出陣で green 化）")
                                .hasSingleBean(WebSocketRelayPublisher.class);
                    });
        }

        @Test
        @DisplayName("relay Subscriber が生成される（skeleton は未宣言のため red）")
        void relaySubscriber_present_whenEnabled() {
            baseRunner
                    .withPropertyValues("mannschaft.websocket.relay.enabled=true")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        // red 駆動: skeleton では未生成 → 失敗
                        assertThat(context)
                                .as("flag ON では WebSocketRelaySubscriber が Bean として生成されること（出陣で green 化）")
                                .hasSingleBean(WebSocketRelaySubscriber.class);
                    });
        }
    }
}
