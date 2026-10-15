package com.mannschaft.app.reservation.event;

import com.mannschaft.app.reservation.service.ReservationWaitlistService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListenerMethodProcessor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.config.TransactionManagementConfigUtils;
import org.springframework.transaction.event.TransactionalEventListenerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link ReservationWaitlistNotificationEventListener} の ApplicationContext ロード検証（軽量・スライス）。
 *
 * <p>本リスナーは {@code @Async("event-pool")} ＋ {@code @TransactionalEventListener(AFTER_COMMIT)} を
 * 併用する。リスナー自身には {@code @Transactional} を付けず（DB 書き込みは委譲先 Service の
 * {@code REQUIRES_NEW} が担う）、登録時バリデーション失敗で全 {@code @SpringBootTest} が巻き添えで落ちる
 * 地雷（feedback_transactional_event_listener_requires_new）を回避していることを、最小 context を実起動して
 * 番人化する（{@link ReservationReminderEventListenerContextTest} に倣う）。</p>
 */
@DisplayName("ReservationWaitlistNotificationEventListener ApplicationContext ロード検証 (F03.4.5 §6.1)")
class ReservationWaitlistNotificationEventListenerContextTest {

    @Configuration
    @EnableAsync
    static class ListenerSliceConfig {

        @Bean(name = TransactionManagementConfigUtils.TRANSACTIONAL_EVENT_LISTENER_FACTORY_BEAN_NAME)
        TransactionalEventListenerFactory transactionalEventListenerFactory() {
            return new TransactionalEventListenerFactory();
        }

        @Bean
        EventListenerMethodProcessor eventListenerMethodProcessor() {
            return new EventListenerMethodProcessor();
        }

        @Bean
        ReservationWaitlistService reservationWaitlistService() {
            return Mockito.mock(ReservationWaitlistService.class);
        }

        @Bean
        ReservationWaitlistNotificationEventListener reservationWaitlistNotificationEventListener(
                ReservationWaitlistService waitlistService) {
            return new ReservationWaitlistNotificationEventListener(waitlistService);
        }
    }

    @Test
    @DisplayName("@Async+@TransactionalEventListener(AFTER_COMMIT) リスナーで context が起動する")
    void contextロード成功() {
        assertThatCode(() -> {
            try (AnnotationConfigApplicationContext ctx =
                         new AnnotationConfigApplicationContext(ListenerSliceConfig.class)) {
                assertThat(ctx.getBean(ReservationWaitlistNotificationEventListener.class)).isNotNull();
            }
        }).doesNotThrowAnyException();
    }
}
