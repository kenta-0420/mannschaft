package com.mannschaft.app.reservation.event;

import com.mannschaft.app.reservation.service.ReservationPolicyService;
import com.mannschaft.app.reservation.service.ReservationReminderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListenerMethodProcessor;
import org.springframework.transaction.config.TransactionManagementConfigUtils;
import org.springframework.transaction.event.TransactionalEventListenerFactory;

import java.time.Clock;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link ReservationReminderEventListener} の ApplicationContext ロード検証（軽量・スライス）。
 *
 * <p>本リスナーは DB 書き込みを伴うため {@code @TransactionalEventListener(AFTER_COMMIT)} ＋
 * {@code @Transactional(REQUIRES_NEW)} を付与している。AFTER_COMMIT は確定 TX のコミット後に発火し
 * その時点では実行中の TX が無いため、もし素の {@code @Transactional}（=REQUIRED）を付けてしまうと
 * {@code TransactionalEventListenerFactory} の登録時に起動が失敗し、全 {@code @SpringBootTest} が
 * 巻き添えで落ちる（既知の重大地雷: feedback_transactional_event_listener_requires_new）。</p>
 *
 * <p>そこで重い {@code @SpringBootTest} を持ち込まず、{@code @TransactionalEventListener} の解決に必須の
 * {@link TransactionalEventListenerFactory} ＋ {@link EventListenerMethodProcessor} と mock 依存のみの最小 context を
 * 実起動し、{@code REQUIRES_NEW} 付きリスナーが起動時バリデーションを通過することを「実際に context を起動して」確証する。
 * 純 Mockito 単体テストでは見えず CI シャードで初めて出る地雷を番人化する。</p>
 */
@DisplayName("ReservationReminderEventListener ApplicationContext ロード検証 (F03.4 ⑥)")
class ReservationReminderEventListenerContextTest {

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
        ReservationPolicyService reservationPolicyService() {
            return Mockito.mock(ReservationPolicyService.class);
        }

        @Bean
        ReservationReminderService reservationReminderService() {
            return Mockito.mock(ReservationReminderService.class);
        }

        @Bean
        Clock clock() {
            return Clock.system(ZoneOffset.UTC);
        }

        @Bean
        ReservationReminderEventListener reservationReminderEventListener(
                ReservationPolicyService policyService,
                ReservationReminderService reminderService,
                Clock clock) {
            return new ReservationReminderEventListener(policyService, reminderService, clock);
        }
    }

    @Test
    @DisplayName("@TransactionalEventListener(AFTER_COMMIT)+@Transactional(REQUIRES_NEW) リスナーで context が起動する")
    void contextロード成功() {
        assertThatCode(() -> {
            try (AnnotationConfigApplicationContext ctx =
                         new AnnotationConfigApplicationContext(ListenerSliceConfig.class)) {
                ReservationReminderEventListener bean = ctx.getBean(ReservationReminderEventListener.class);
                assertThat(bean).isNotNull();
            }
        }).doesNotThrowAnyException();
    }
}
