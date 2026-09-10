package com.mannschaft.app.notification.service;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * CMP-260909-1446 AC-06 の番人。
 *
 * <p>DB へ入る {@code created_at} を SQL リテラル {@code UTC_TIMESTAMP()} へ移す是正によって、
 * <b>配信（WebSocket/Push）ペイロードに載る in-memory {@code createdAt} の意味が変わっていない</b>
 * ことを固定する。配信ペイロードの {@code createdAt} は JPA 経路（{@code @PrePersist} の
 * {@code LocalDateTime.now()}）と同じ<b>サーバ既定ゾーン（JST）の壁時計</b>であり、
 * DB 格納値（UTC 壁時計）とは別物である。ここを取り違えて「配信ペイロードも UTC にする」と、
 * FE が受け取る時刻だけが 9 時間ずれる（本欠陥の裏返し）。</p>
 */
@DisplayName("バルク fan-out の配信ペイロード createdAt の意味は不変（JST 壁時計・CMP-260909-1446 AC-06）")
class NotificationBulkFanoutDispatchPayloadTest {

    @Test
    @DisplayName("dispatchBatch へ渡るエンティティの createdAt はサーバ既定ゾーンの壁時計である")
    void 配信ペイロードのcreatedAtはサーバ既定ゾーンの壁時計() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        NotificationDispatchService dispatchService = mock(NotificationDispatchService.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

        NotificationBulkFanoutService sut =
                new NotificationBulkFanoutService(jdbcTemplate, dispatchService, txManager);

        LocalDateTime before = LocalDateTime.now();
        sut.insertAndDispatchChunk(
                List.of(1L, 2L), "EVENT_CREATED", NotificationPriority.NORMAL, "件名", "本文",
                "VILLAGE_EVENT", null, NotificationScopeType.SYSTEM, null, "/villages/x", null, null);
        LocalDateTime after = LocalDateTime.now();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NotificationEntity>> captor =
                (ArgumentCaptor<List<NotificationEntity>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(dispatchService).dispatchBatch(captor.capture());

        List<NotificationEntity> dispatched = captor.getValue();
        assertThat(dispatched).hasSize(2);
        assertThat(dispatched).allSatisfy(n -> {
            assertThat(n.getCreatedAt())
                    .as("配信ペイロードの createdAt は JPA 経路の in-memory 値と同じ「今のサーバ壁時計」")
                    .isBetween(before.minusSeconds(1), after.plusSeconds(1));
        });
        assertThat(dispatched).extracting(NotificationEntity::getCreatedAt)
                .as("チャンク内の全行が同一の in-memory createdAt を共有する")
                .containsOnly(dispatched.get(0).getCreatedAt());

        // 念のため: UTC 壁時計へ寄せてしまう退行（配信側まで UTC 化する誤り）を検出する。
        LocalDateTime utcNow = LocalDateTime.now(java.time.ZoneOffset.UTC);
        if (Duration.between(utcNow, before).abs().toMinutes() > 1) {
            assertThat(Duration.between(utcNow, dispatched.get(0).getCreatedAt()).abs())
                    .as("配信ペイロードが UTC 壁時計になっていない（JST 壁時計のままである）")
                    .isGreaterThan(Duration.ofMinutes(1));
        }
    }
}
