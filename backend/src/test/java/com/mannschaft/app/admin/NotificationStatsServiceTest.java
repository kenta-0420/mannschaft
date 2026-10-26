package com.mannschaft.app.admin;

import com.mannschaft.app.admin.dto.NotificationStatsResponse;
import com.mannschaft.app.admin.entity.NotificationDeliveryStatsEntity;
import com.mannschaft.app.admin.repository.NotificationDeliveryStatsRepository;
import com.mannschaft.app.admin.service.NotificationStatsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link NotificationStatsService} の単体テスト。
 * 通知配信統計の取得を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationStatsService 単体テスト")
class NotificationStatsServiceTest {

    @Mock
    private NotificationDeliveryStatsRepository statsRepository;

    @Mock
    private AdminMapper adminMapper;

    @InjectMocks
    private NotificationStatsService service;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final LocalDate FROM = LocalDate.of(2026, 3, 1);
    private static final LocalDate TO = LocalDate.of(2026, 3, 31);

    private NotificationDeliveryStatsEntity createStatsEntity() {
        return NotificationDeliveryStatsEntity.builder()
                .date(FROM)
                .channel(NotificationChannel.EMAIL)
                .sentCount(100)
                .deliveredCount(95)
                .failedCount(3)
                .bounceCount(2)
                .build();
    }

    private NotificationStatsResponse createStatsResponse() {
        return new NotificationStatsResponse(1L, FROM, "EMAIL", 100, 95, 3, 2);
    }

    // ========================================
    // getStats
    // ========================================

    @Nested
    @DisplayName("getStats")
    class GetStats {

        @Test
        @DisplayName("正常系: 日付範囲の統計が返却される")
        void 取得_日付範囲_統計返却() {
            // Given
            List<NotificationDeliveryStatsEntity> entities = List.of(createStatsEntity());
            List<NotificationStatsResponse> responses = List.of(createStatsResponse());
            given(statsRepository.findByDateBetweenOrderByDateDescChannelAsc(FROM, TO)).willReturn(entities);
            given(adminMapper.toNotificationStatsResponseList(entities)).willReturn(responses);

            // When
            List<NotificationStatsResponse> result = service.getStats(FROM, TO);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getChannel()).isEqualTo("EMAIL");
            assertThat(result.get(0).getSentCount()).isEqualTo(100);
            verify(statsRepository).findByDateBetweenOrderByDateDescChannelAsc(FROM, TO);
        }

        @Test
        @DisplayName("正常系: データなしの場合空リストが返却される")
        void 取得_データなし_空リスト() {
            // Given
            given(statsRepository.findByDateBetweenOrderByDateDescChannelAsc(FROM, TO)).willReturn(List.of());
            given(adminMapper.toNotificationStatsResponseList(List.of())).willReturn(List.of());

            // When
            List<NotificationStatsResponse> result = service.getStats(FROM, TO);

            // Then
            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // getStatsByChannel
    // ========================================

    @Nested
    @DisplayName("getStatsByChannel")
    class GetStatsByChannel {

        @Test
        @DisplayName("正常系: チャネル別統計が返却される")
        void 取得_チャネル指定_統計返却() {
            // Given
            List<NotificationDeliveryStatsEntity> entities = List.of(createStatsEntity());
            List<NotificationStatsResponse> responses = List.of(createStatsResponse());
            given(statsRepository.findByChannelAndDateBetweenOrderByDateDesc(NotificationChannel.EMAIL, FROM, TO))
                    .willReturn(entities);
            given(adminMapper.toNotificationStatsResponseList(entities)).willReturn(responses);

            // When
            List<NotificationStatsResponse> result = service.getStatsByChannel("EMAIL", FROM, TO);

            // Then
            assertThat(result).hasSize(1);
            verify(statsRepository).findByChannelAndDateBetweenOrderByDateDesc(NotificationChannel.EMAIL, FROM, TO);
        }

        @Test
        @DisplayName("異常系: 無効なチャネル名でIllegalArgumentException")
        void 取得_無効チャネル_例外() {
            // When / Then
            assertThatThrownBy(() -> service.getStatsByChannel("INVALID_CHANNEL", FROM, TO))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
