package com.mannschaft.app.reservation.service;

import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.reservation.repository.ReservationReminderRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link ReservationReminderDispatchBatchService} の ApplicationContext ロード検証（軽量・スライス）。
 *
 * <p>本バッチは {@code @Scheduled(fixedDelay)} ＋ {@code @Transactional} を付与している。
 * {@code @Scheduled} メソッドはパラメータ無し・戻り値 {@code void} でなければ
 * {@link ScheduledAnnotationBeanPostProcessor} の登録時バリデーションで起動が失敗する。
 * 純 Mockito 単体テストでは見えず CI シャードで初めて出る「起動時バリデーション失敗で全
 * {@code @SpringBootTest} 巻き添え」を防ぐため、{@code @EnableScheduling} を効かせた最小 context を
 * 実起動して {@code @Scheduled} の登録が通ることを番人化する
 * （{@link com.mannschaft.app.reservation.event.ReservationReminderEventListener} の context テストに倣う）。</p>
 */
@DisplayName("ReservationReminderDispatchBatchService ApplicationContext ロード検証 (F03.4 ⑥)")
class ReservationReminderDispatchBatchServiceContextTest {

    @Configuration
    @EnableScheduling
    static class BatchSliceConfig {

        @Bean
        ReservationReminderService reservationReminderService() {
            return Mockito.mock(ReservationReminderService.class);
        }

        @Bean
        ReservationReminderRepository reservationReminderRepository() {
            return Mockito.mock(ReservationReminderRepository.class);
        }

        @Bean
        ReservationRepository reservationRepository() {
            return Mockito.mock(ReservationRepository.class);
        }

        @Bean
        ReservationSlotRepository reservationSlotRepository() {
            return Mockito.mock(ReservationSlotRepository.class);
        }

        @Bean
        NotificationHelper notificationHelper() {
            return Mockito.mock(NotificationHelper.class);
        }

        @Bean
        ReservationReminderDispatchBatchService reservationReminderDispatchBatchService(
                ReservationReminderService reminderService,
                ReservationReminderRepository reminderRepository,
                ReservationRepository reservationRepository,
                ReservationSlotRepository slotRepository,
                NotificationHelper notificationHelper) {
            return new ReservationReminderDispatchBatchService(
                    reminderService, reminderRepository, reservationRepository,
                    slotRepository, notificationHelper);
        }
    }

    @Test
    @DisplayName("@Scheduled(fixedDelay)+@Transactional バッチで context が起動する")
    void contextロード成功() {
        assertThatCode(() -> {
            try (AnnotationConfigApplicationContext ctx =
                         new AnnotationConfigApplicationContext(BatchSliceConfig.class)) {
                ReservationReminderDispatchBatchService bean =
                        ctx.getBean(ReservationReminderDispatchBatchService.class);
                assertThat(bean).isNotNull();
            }
        }).doesNotThrowAnyException();
    }
}
