package com.mannschaft.app.match.live;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListenerMethodProcessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.config.TransactionManagementConfigUtils;
import org.springframework.transaction.event.TransactionalEventListenerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * F08.10 / 07 §J.2 {@link MatchLiveBroadcastListener} の ApplicationContext ロード検証（軽量・スライス）。
 *
 * <p>素の {@code @TransactionalEventListener(AFTER_COMMIT)}（{@code @Transactional} 無し）で ApplicationContext が
 * 起動時バリデーションを通過することを確認する。入口① {@code MatchScoreFixtureListener} のように
 * {@code @Transactional(REQUIRED)} を誤って付けると {@code TransactionalEventListenerFactory} の登録時に
 * 起動が失敗する（feedback: TransactionalEventListener に素の @Transactional(REQUIRED) は context 全滅）。
 * 本リスナーは配信のみで DB 書き込みが無く新規 TX 不要のため、{@code @Transactional} を付けない設計が正しいことを
 * 「実際に context を起動して」確証する（重い {@code @SpringBootTest} を持ち込まず、必要 Bean のみの最小 context）。</p>
 */
@DisplayName("MatchLiveBroadcastListener ApplicationContext ロード検証 (F08.10 / 07 §J)")
class MatchLiveBroadcastListenerContextTest {

    @Configuration
    static class ListenerSliceConfig {

        /** {@code @TransactionalEventListener} を解決するために必須のファクトリ（Spring 標準の登録名で登録）。 */
        @Bean(name = TransactionManagementConfigUtils.TRANSACTIONAL_EVENT_LISTENER_FACTORY_BEAN_NAME)
        TransactionalEventListenerFactory transactionalEventListenerFactory() {
            return new TransactionalEventListenerFactory();
        }

        /** {@code @TransactionalEventListener} 注釈付きメソッドを走査・登録するプロセッサ。 */
        @Bean
        EventListenerMethodProcessor eventListenerMethodProcessor() {
            return new EventListenerMethodProcessor();
        }

        @Bean
        SimpMessagingTemplate simpMessagingTemplate() {
            return Mockito.mock(SimpMessagingTemplate.class);
        }

        @Bean
        MatchLiveBroadcastListener matchLiveBroadcastListener(SimpMessagingTemplate template) {
            return new MatchLiveBroadcastListener(template);
        }
    }

    @Test
    @DisplayName("素の @TransactionalEventListener(AFTER_COMMIT) リスナーで context が起動する（REQUIRES_NEW 不要）")
    void contextロード成功() {
        assertThatCode(() -> {
            try (AnnotationConfigApplicationContext ctx =
                         new AnnotationConfigApplicationContext(ListenerSliceConfig.class)) {
                MatchLiveBroadcastListener bean = ctx.getBean(MatchLiveBroadcastListener.class);
                assertThat(bean).isNotNull();
            }
        }).doesNotThrowAnyException();
    }
}
