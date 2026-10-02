package com.mannschaft.app.reservation.service;

import com.mannschaft.app.reservation.repository.ReservationSlotTemplateRepository;
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
 * {@link ReservationSlotGenerationBatchService} の ApplicationContext ロード検証（軽量・スライス）。
 *
 * <p>F03.4.2 F-9③ の番人: {@code @Scheduled(cron)} メソッドはパラメータ無し・戻り値 {@code void}・
 * cron 式が妥当でなければ {@link ScheduledAnnotationBeanPostProcessor} の登録時バリデーションで
 * 起動が失敗する。純 Mockito 単体テストでは見えず CI シャードで初めて出る
 * 「起動時バリデーション失敗で全 {@code @SpringBootTest} 巻き添え」を防ぐため、
 * {@code @EnableScheduling} を効かせた最小 context を実起動して登録が通ることを番人化する
 * （{@link ReservationReminderDispatchBatchServiceContextTest} の写経・親 §3 の既知地雷対策）。</p>
 */
@DisplayName("ReservationSlotGenerationBatchService ApplicationContext ロード検証 (F03.4.2 F-9③)")
class ReservationSlotGenerationBatchServiceContextTest {

    @Configuration
    @EnableScheduling
    static class BatchSliceConfig {

        @Bean
        ReservationSlotTemplateRepository reservationSlotTemplateRepository() {
            return Mockito.mock(ReservationSlotTemplateRepository.class);
        }

        @Bean
        ReservationSlotGenerationService reservationSlotGenerationService() {
            return Mockito.mock(ReservationSlotGenerationService.class);
        }

        @Bean
        ReservationSlotGenerationBatchService reservationSlotGenerationBatchService(
                ReservationSlotTemplateRepository templateRepository,
                ReservationSlotGenerationService generationService) {
            return new ReservationSlotGenerationBatchService(templateRepository, generationService);
        }
    }

    @Test
    @DisplayName("@Scheduled(cron 日次 0:15 JST) バッチで context が起動する（cron 式の登録時バリデーション通過）")
    void contextロード成功() {
        assertThatCode(() -> {
            try (AnnotationConfigApplicationContext ctx =
                         new AnnotationConfigApplicationContext(BatchSliceConfig.class)) {
                ReservationSlotGenerationBatchService bean =
                        ctx.getBean(ReservationSlotGenerationBatchService.class);
                assertThat(bean).isNotNull();
            }
        }).doesNotThrowAnyException();
    }
}
