package com.mannschaft.app.reservation.service;

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
 * {@link ReservationWaitlistCleanupBatchService} の ApplicationContext ロード検証（軽量・スライス）。
 *
 * <p>本バッチは {@code @Scheduled(cron)} を付与している。{@code @Scheduled} メソッドはパラメータ無し・
 * 戻り値 {@code void} でなければ {@link ScheduledAnnotationBeanPostProcessor} の登録時バリデーションで
 * 起動が失敗する。CI シャードで初めて出る「登録時バリデーション失敗で全 {@code @SpringBootTest} 巻き添え」を
 * 防ぐため、{@code @EnableScheduling} を効かせた最小 context を実起動して番人化する。</p>
 */
@DisplayName("ReservationWaitlistCleanupBatchService ApplicationContext ロード検証 (F03.4.5 §6.1)")
class ReservationWaitlistCleanupBatchServiceContextTest {

    @Configuration
    @EnableScheduling
    static class BatchSliceConfig {

        @Bean
        ReservationWaitlistService reservationWaitlistService() {
            return Mockito.mock(ReservationWaitlistService.class);
        }

        @Bean
        ReservationWaitlistCleanupBatchService reservationWaitlistCleanupBatchService(
                ReservationWaitlistService waitlistService) {
            return new ReservationWaitlistCleanupBatchService(waitlistService);
        }
    }

    @Test
    @DisplayName("@Scheduled(cron) バッチで context が起動する")
    void contextロード成功() {
        assertThatCode(() -> {
            try (AnnotationConfigApplicationContext ctx =
                         new AnnotationConfigApplicationContext(BatchSliceConfig.class)) {
                assertThat(ctx.getBean(ReservationWaitlistCleanupBatchService.class)).isNotNull();
            }
        }).doesNotThrowAnyException();
    }
}
