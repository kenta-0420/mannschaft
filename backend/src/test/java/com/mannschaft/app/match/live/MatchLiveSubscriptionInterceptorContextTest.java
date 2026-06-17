package com.mannschaft.app.match.live;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.config.WebSocketAuthChannelInterceptor;
import com.mannschaft.app.config.WebSocketConfig;
import com.mannschaft.app.match.service.MatchAccessService;
import com.mannschaft.app.reservation.ws.EmergencyClosureSubscriptionInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.support.ChannelInterceptor;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * F08.10 / 07 §J.3 購読認可インターセプタを追加した {@link WebSocketConfig} の ApplicationContext
 * ロード検証（軽量・スライス）。
 *
 * <p>検証観点:</p>
 * <ul>
 *   <li>{@link MatchLiveSubscriptionInterceptor} を追加併存させても context が壊れない（Bean 解決成功）。</li>
 *   <li>inbound channel に既存 CONNECT インターセプタ（{@link WebSocketAuthChannelInterceptor}）と
 *       購読認可インターセプタが<b>この順</b>で登録される（認証 → 認可・順序が重要）。</li>
 * </ul>
 *
 * <p>重い {@code @SpringBootTest} を持ち込まず、{@code WebSocketConfig} と依存 Bean のみの最小 context で
 * 実際に起動して確証する。</p>
 */
@DisplayName("WebSocketConfig + MatchLiveSubscriptionInterceptor ApplicationContext ロード検証 (F08.10 / 07 §J.3)")
class MatchLiveSubscriptionInterceptorContextTest {

    @Configuration
    static class ConfigSlice {

        @Bean
        WebSocketAuthChannelInterceptor authChannelInterceptor() {
            return Mockito.mock(WebSocketAuthChannelInterceptor.class);
        }

        @Bean
        MatchLiveSubscriptionInterceptor matchLiveSubscriptionInterceptor() {
            return new MatchLiveSubscriptionInterceptor(Mockito.mock(MatchAccessService.class));
        }

        @Bean
        EmergencyClosureSubscriptionInterceptor emergencyClosureSubscriptionInterceptor() {
            return new EmergencyClosureSubscriptionInterceptor(Mockito.mock(AccessControlService.class));
        }

        @Bean
        WebSocketConfig webSocketConfig(WebSocketAuthChannelInterceptor auth,
                                        MatchLiveSubscriptionInterceptor sub,
                                        EmergencyClosureSubscriptionInterceptor closureSub) {
            return new WebSocketConfig(auth, sub, closureSub);
        }
    }

    @Test
    @DisplayName("購読認可インターセプタ追加併存で context が起動し WebSocketConfig が解決できる")
    void contextロード成功() {
        assertThatCode(() -> {
            try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(ConfigSlice.class)) {
                assertThat(ctx.getBean(WebSocketConfig.class)).isNotNull();
                assertThat(ctx.getBean(WebSocketAuthChannelInterceptor.class)).isNotNull();
                assertThat(ctx.getBean(MatchLiveSubscriptionInterceptor.class)).isNotNull();
                assertThat(ctx.getBean(EmergencyClosureSubscriptionInterceptor.class)).isNotNull();
            }
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("inbound channel に認証 → match live認可 → 臨時休業認可 の順で 3 つのインターセプタが登録される（CONNECT 既存は不変・併存）")
    void inboundChannelに認証の後段で購読認可が登録される() {
        WebSocketAuthChannelInterceptor auth = Mockito.mock(WebSocketAuthChannelInterceptor.class);
        MatchLiveSubscriptionInterceptor sub =
                new MatchLiveSubscriptionInterceptor(Mockito.mock(MatchAccessService.class));
        EmergencyClosureSubscriptionInterceptor closureSub =
                new EmergencyClosureSubscriptionInterceptor(Mockito.mock(AccessControlService.class));
        WebSocketConfig config = new WebSocketConfig(auth, sub, closureSub);

        ChannelRegistration registration = new ChannelRegistration();
        config.configureClientInboundChannel(registration);

        List<ChannelInterceptor> interceptors = extractInterceptors(registration);
        assertThat(interceptors).hasSize(3);
        // 認証（CONNECT で userId 確定）が先、購読認可（SUBSCRIBE）が後段に並ぶ。
        assertThat(interceptors.get(0)).isSameAs(auth);
        assertThat(interceptors.get(1)).isSameAs(sub);
        assertThat(interceptors.get(2)).isSameAs(closureSub);
    }

    /** {@link ChannelRegistration#getInterceptors()}（protected）を反射で呼び出して登録済み一覧を取り出す。 */
    @SuppressWarnings("unchecked")
    private List<ChannelInterceptor> extractInterceptors(ChannelRegistration registration) {
        try {
            Method method = ChannelRegistration.class.getDeclaredMethod("getInterceptors");
            method.setAccessible(true);
            return (List<ChannelInterceptor>) method.invoke(registration);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("ChannelRegistration.getInterceptors() を反射呼出できませんでした", e);
        }
    }
}
