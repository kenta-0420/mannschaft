package com.mannschaft.app.reservation.event;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.service.NotificationHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListenerMethodProcessor;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.config.TransactionManagementConfigUtils;
import org.springframework.transaction.event.TransactionalEventListenerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link ReservationForceCancelNotificationEventListener} の ApplicationContext ロード検証
 * （軽量・スライス・F03.4.5 §6.2 W2-5）。
 *
 * <p>本リスナーは {@code @Async("event-pool")} ＋ {@code @TransactionalEventListener(AFTER_COMMIT)} を
 * 併用する。リスナー自身には {@code @Transactional} を付けていない
 * （AFTER_COMMIT 時点では ambient tx が無く、通知失敗で全 {@code @SpringBootTest} を巻き添えにする
 * 地雷を踏むため・{@code feedback_transactional_event_listener_requires_new}）。
 * その構成で context が起動することを最小 context の実起動で番人化する
 * （{@link ReservationWaitlistNotificationEventListenerContextTest} に倣う）。</p>
 */
@DisplayName("ReservationForceCancelNotificationEventListener ApplicationContext ロード検証 (F03.4.5 §6.2)")
class ReservationForceCancelNotificationEventListenerContextTest {

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
        NotificationHelper notificationHelper() {
            return Mockito.mock(NotificationHelper.class);
        }

        @Bean
        UserLocaleCache userLocaleCache() {
            return Mockito.mock(UserLocaleCache.class);
        }

        @Bean
        MessageSource messageSource() {
            return new StaticMessageSource();
        }

        @Bean
        ReservationForceCancelNotificationEventListener reservationForceCancelNotificationEventListener(
                NotificationHelper notificationHelper, UserLocaleCache userLocaleCache, MessageSource messageSource) {
            return new ReservationForceCancelNotificationEventListener(
                    notificationHelper, userLocaleCache, messageSource);
        }
    }

    @Test
    @DisplayName("@Async+@TransactionalEventListener(AFTER_COMMIT) リスナーで context が起動する")
    void contextロード成功() {
        assertThatCode(() -> {
            try (AnnotationConfigApplicationContext ctx =
                         new AnnotationConfigApplicationContext(ListenerSliceConfig.class)) {
                assertThat(ctx.getBean(ReservationForceCancelNotificationEventListener.class)).isNotNull();
            }
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("通知種別は既存の RESERVATION_CANCELLED を再利用する（共有 enum に種別を足さない）")
    void 既存通知種別を再利用する() {
        // 全ドメイン共有の NotificationType に種別を足すと、種別数ガード（NotificationTypeTest）や
        // 通知設定 UI まで巻き添えにする。実態が「管理者による予約キャンセル」と完全に一致するため
        // 既存種別を再利用する判断を、名前の一致で機械的に固定する。
        assertThat(ReservationForceCancelNotificationEventListener.NOTIFICATION_TYPE)
                .isEqualTo(com.mannschaft.app.notification.NotificationType.RESERVATION_CANCELLED.name());
        assertThat(ReservationForceCancelNotificationEventListener.SOURCE_TYPE)
                .isEqualTo(com.mannschaft.app.notification.NotificationType.RESERVATION_CANCELLED.getSourceType());
    }
}
