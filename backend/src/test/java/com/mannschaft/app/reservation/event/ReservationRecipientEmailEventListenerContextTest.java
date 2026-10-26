package com.mannschaft.app.reservation.event;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import com.mannschaft.app.reservation.repository.ReservationNotificationRecipientRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListenerMethodProcessor;
import org.springframework.transaction.config.TransactionManagementConfigUtils;
import org.springframework.transaction.event.TransactionalEventListenerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link ReservationRecipientEmailEventListener} の ApplicationContext ロード検証（軽量・スライス・機能D D-10）。
 *
 * <p>本リスナーは DB 書き込み（{@code EmailOutboxService.enqueue}）を伴うため
 * {@code @TransactionalEventListener(AFTER_COMMIT)} ＋ {@code @Transactional(REQUIRES_NEW)} を付与している。
 * もし素の {@code @Transactional}（=REQUIRED）を付けてしまうと {@code TransactionalEventListenerFactory} の
 * 登録時に起動が失敗し、全 {@code @SpringBootTest} が巻き添えで落ちる（既知の重大地雷:
 * feedback_transactional_event_listener_requires_new。リマインドリスナーと同じ轍）。</p>
 *
 * <p>重い {@code @SpringBootTest} を持ち込まず、{@code @TransactionalEventListener} の解決に必須の
 * {@link TransactionalEventListenerFactory} ＋ {@link EventListenerMethodProcessor} と mock 依存のみの
 * 最小 context を実起動し、{@code REQUIRES_NEW} 付きリスナーが起動時バリデーションを通過することを
 * 「実際に context を起動して」確証する。純 Mockito 単体テストでは見えず CI シャードで初めて出る地雷を番人化する。</p>
 */
@DisplayName("ReservationRecipientEmailEventListener ApplicationContext ロード検証 (機能D D-10)")
class ReservationRecipientEmailEventListenerContextTest {

    @Configuration
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
        ReservationNotificationRecipientRepository recipientRepository() {
            return Mockito.mock(ReservationNotificationRecipientRepository.class);
        }

        @Bean
        ReservationRepository reservationRepository() {
            return Mockito.mock(ReservationRepository.class);
        }

        @Bean
        ReservationSlotRepository slotRepository() {
            return Mockito.mock(ReservationSlotRepository.class);
        }

        @Bean
        NameResolverService nameResolverService() {
            return Mockito.mock(NameResolverService.class);
        }

        @Bean
        EmailOutboxService emailOutboxService() {
            return Mockito.mock(EmailOutboxService.class);
        }

        @Bean
        ReservationRecipientEmailEventListener reservationRecipientEmailEventListener(
                ReservationNotificationRecipientRepository recipientRepository,
                ReservationRepository reservationRepository,
                ReservationSlotRepository slotRepository,
                NameResolverService nameResolverService,
                EmailOutboxService emailOutboxService) {
            return new ReservationRecipientEmailEventListener(
                    recipientRepository, reservationRepository, slotRepository,
                    nameResolverService, emailOutboxService);
        }
    }

    @Test
    @DisplayName("@TransactionalEventListener(AFTER_COMMIT)+@Transactional(REQUIRES_NEW) リスナーで context が起動する")
    void contextロード成功() {
        assertThatCode(() -> {
            try (AnnotationConfigApplicationContext ctx =
                         new AnnotationConfigApplicationContext(ListenerSliceConfig.class)) {
                ReservationRecipientEmailEventListener bean =
                        ctx.getBean(ReservationRecipientEmailEventListener.class);
                assertThat(bean).isNotNull();
            }
        }).doesNotThrowAnyException();
    }
}
