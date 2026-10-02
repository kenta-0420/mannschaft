package com.mannschaft.app.cms.service;

import java.time.Clock;

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
 * {@link BlogScheduledPublishBatchService} の ApplicationContext ロード検証（軽量スライス・AC-14）。
 *
 * <p>{@code @Scheduled} メソッドはパラメータ無しでなければ {@link ScheduledAnnotationBeanPostProcessor} の
 * 登録時バリデーションで起動が失敗する。CI シャードで初めて出る「登録時バリデーション失敗で全
 * {@code @SpringBootTest} 巻き添え」を防ぐため、{@code @EnableScheduling} を効かせた最小 context を
 * 実起動して番人化する（{@code ReservationPendingExpireBatchServiceContextTest} の写経）。</p>
 */
@DisplayName("BlogScheduledPublishBatchService ApplicationContext ロード検証（issue #2616 / AC-14）")
class BlogScheduledPublishBatchServiceContextTest {

    @Configuration
    @EnableScheduling
    static class BatchSliceConfig {

        @Bean
        BlogScheduledPublishService blogScheduledPublishService() {
            return Mockito.mock(BlogScheduledPublishService.class);
        }

        @Bean
        BlogScheduledPublishBatchService blogScheduledPublishBatchService(
                BlogScheduledPublishService scheduledPublishService) {
            return new BlogScheduledPublishBatchService(scheduledPublishService, Clock.systemUTC());
        }
    }

    @Test
    @DisplayName("AC-14: @Scheduled(fixedDelay) バッチで context が起動する")
    void ac14_contextロード成功() {
        assertThatCode(() -> {
            try (AnnotationConfigApplicationContext ctx =
                         new AnnotationConfigApplicationContext(BatchSliceConfig.class)) {
                assertThat(ctx.getBean(BlogScheduledPublishBatchService.class)).isNotNull();
            }
        }).doesNotThrowAnyException();
    }
}
