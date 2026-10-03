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
 * {@link ReservationPendingExpireBatchService} の ApplicationContext ロード検証（軽量・スライス）。
 *
 * <p>{@code @Scheduled} メソッドはパラメータ無しでなければ {@link ScheduledAnnotationBeanPostProcessor} の
 * 登録時バリデーションで起動が失敗する。CI シャードで初めて出る「登録時バリデーション失敗で全
 * {@code @SpringBootTest} 巻き添え」を防ぐため、{@code @EnableScheduling} を効かせた最小 context を
 * 実起動して番人化する（{@link ReservationWaitlistCleanupBatchServiceContextTest} の写経）。</p>
 *
 * <p>本バッチのメソッドは失効件数（{@code int}）を返す。非 void の {@code @Scheduled} が
 * 将来の Spring バージョンで拒否されるようになった場合、本テストが最初に赤くなる。</p>
 */
@DisplayName("ReservationPendingExpireBatchService ApplicationContext ロード検証 (F03.4.5 §6.3)")
class ReservationPendingExpireBatchServiceContextTest {

    @Configuration
    @EnableScheduling
    static class BatchSliceConfig {

        @Bean
        ReservationPendingExpireService reservationPendingExpireService() {
            return Mockito.mock(ReservationPendingExpireService.class);
        }

        @Bean
        ReservationPendingExpireBatchService reservationPendingExpireBatchService(
                ReservationPendingExpireService pendingExpireService) {
            return new ReservationPendingExpireBatchService(pendingExpireService);
        }
    }

    @Test
    @DisplayName("@Scheduled(fixedDelay) バッチで context が起動する")
    void contextロード成功() {
        assertThatCode(() -> {
            try (AnnotationConfigApplicationContext ctx =
                         new AnnotationConfigApplicationContext(BatchSliceConfig.class)) {
                assertThat(ctx.getBean(ReservationPendingExpireBatchService.class)).isNotNull();
            }
        }).doesNotThrowAnyException();
    }
}
