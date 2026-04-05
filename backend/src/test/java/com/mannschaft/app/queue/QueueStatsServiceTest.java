package com.mannschaft.app.queue;

import com.mannschaft.app.queue.dto.DailyStatsResponse;
import com.mannschaft.app.queue.dto.QueueStatusResponse;
import com.mannschaft.app.queue.entity.QueueCounterEntity;
import com.mannschaft.app.queue.entity.QueueDailyStatsEntity;
import com.mannschaft.app.queue.entity.QueueTicketEntity;
import com.mannschaft.app.queue.repository.QueueCounterRepository;
import com.mannschaft.app.queue.repository.QueueDailyStatsRepository;
import com.mannschaft.app.queue.repository.QueueTicketRepository;
import com.mannschaft.app.queue.service.QueueStatsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * {@link QueueStatsService} の単体テスト。
 * リアルタイムステータスと日次統計を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QueueStatsService 単体テスト")
class QueueStatsServiceTest {

    @Mock
    private QueueTicketRepository ticketRepository;

    @Mock
    private QueueCounterRepository counterRepository;

    @Mock
    private QueueDailyStatsRepository dailyStatsRepository;

    @Mock
    private QueueMapper queueMapper;

    @InjectMocks
    private QueueStatsService queueStatsService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long CATEGORY_ID = 1L;
    private static final Long COUNTER_ID = 10L;
    private static final Long SCOPE_ID = 100L;
    private static final QueueScopeType SCOPE_TYPE = QueueScopeType.TEAM;

    private QueueCounterEntity createCounterEntity() {
        return QueueCounterEntity.builder()
                .categoryId(CATEGORY_ID)
                .name("窓口1")
                .avgServiceMinutes((short) 10)
                .maxQueueSize((short) 50)
                .build();
    }

    // ========================================
    // getQueueStatus
    // ========================================

    @Nested
    @DisplayName("getQueueStatus")
    class GetQueueStatus {

        @Test
        @DisplayName("キューステータス取得_待ちあり_ステータス返却")
        void キューステータス取得_待ちあり_ステータス返却() {
            // Given
            QueueCounterEntity counter = createCounterEntity();
            QueueTicketEntity waitingTicket = QueueTicketEntity.builder()
                    .categoryId(CATEGORY_ID)
                    .counterId(COUNTER_ID)
                    .ticketNumber("Q001")
                    .source(TicketSource.ONLINE)
                    .status(TicketStatus.WAITING)
                    .position(1)
                    .issuedDate(LocalDate.now())
                    .build();

            given(counterRepository.findByCategoryIdOrderByDisplayOrderAsc(CATEGORY_ID))
                    .willReturn(List.of(counter));
            given(ticketRepository.findByCounterIdAndIssuedDateAndStatusOrderByPositionAsc(
                    any(), any(LocalDate.class), eq(TicketStatus.WAITING)))
                    .willReturn(List.of(waitingTicket));
            given(ticketRepository.findByCounterIdAndIssuedDateAndStatusOrderByPositionAsc(
                    any(), any(LocalDate.class), eq(TicketStatus.SERVING)))
                    .willReturn(List.of());
            given(queueMapper.toTicketResponseList(any())).willReturn(List.of());

            // When
            List<QueueStatusResponse> result = queueStatsService.getQueueStatus(List.of(CATEGORY_ID));

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getWaitingCount()).isEqualTo(1);
            assertThat(result.get(0).getEstimatedWaitMinutes()).isEqualTo(10);
        }

        @Test
        @DisplayName("キューステータス取得_待ちなし_ゼロ返却")
        void キューステータス取得_待ちなし_ゼロ返却() {
            // Given
            QueueCounterEntity counter = createCounterEntity();

            given(counterRepository.findByCategoryIdOrderByDisplayOrderAsc(CATEGORY_ID))
                    .willReturn(List.of(counter));
            given(ticketRepository.findByCounterIdAndIssuedDateAndStatusOrderByPositionAsc(
                    any(), any(LocalDate.class), eq(TicketStatus.WAITING)))
                    .willReturn(List.of());
            given(ticketRepository.findByCounterIdAndIssuedDateAndStatusOrderByPositionAsc(
                    any(), any(LocalDate.class), eq(TicketStatus.SERVING)))
                    .willReturn(List.of());
            given(queueMapper.toTicketResponseList(any())).willReturn(List.of());

            // When
            List<QueueStatusResponse> result = queueStatsService.getQueueStatus(List.of(CATEGORY_ID));

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getWaitingCount()).isEqualTo(0);
            assertThat(result.get(0).getCurrentTicketNumber()).isNull();
        }

        @Test
        @DisplayName("キューステータス取得_対応中あり_チケット番号返却")
        void キューステータス取得_対応中あり_チケット番号返却() {
            // Given
            QueueCounterEntity counter = createCounterEntity();
            QueueTicketEntity servingTicket = QueueTicketEntity.builder()
                    .categoryId(CATEGORY_ID)
                    .counterId(COUNTER_ID)
                    .ticketNumber("Q001")
                    .source(TicketSource.ONLINE)
                    .status(TicketStatus.SERVING)
                    .position(1)
                    .issuedDate(LocalDate.now())
                    .build();

            given(counterRepository.findByCategoryIdOrderByDisplayOrderAsc(CATEGORY_ID))
                    .willReturn(List.of(counter));
            given(ticketRepository.findByCounterIdAndIssuedDateAndStatusOrderByPositionAsc(
                    any(), any(LocalDate.class), eq(TicketStatus.WAITING)))
                    .willReturn(List.of());
            given(ticketRepository.findByCounterIdAndIssuedDateAndStatusOrderByPositionAsc(
                    any(), any(LocalDate.class), eq(TicketStatus.SERVING)))
                    .willReturn(List.of(servingTicket));
            given(queueMapper.toTicketResponseList(any())).willReturn(List.of());

            // When
            List<QueueStatusResponse> result = queueStatsService.getQueueStatus(List.of(CATEGORY_ID));

            // Then
            assertThat(result.get(0).getCurrentTicketNumber()).isEqualTo("Q001");
        }

        @Test
        @DisplayName("キューステータス取得_カテゴリ空リスト_空リスト返却")
        void キューステータス取得_カテゴリ空リスト_空リスト返却() {
            // When
            List<QueueStatusResponse> result = queueStatsService.getQueueStatus(List.of());

            // Then
            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // getDailyStats
    // ========================================

    @Nested
    @DisplayName("getDailyStats")
    class GetDailyStats {

        @Test
        @DisplayName("日次統計取得_正常_リスト返却")
        void 日次統計取得_正常_リスト返却() {
            // Given
            LocalDate from = LocalDate.of(2026, 3, 1);
            LocalDate to = LocalDate.of(2026, 3, 25);
            QueueDailyStatsEntity statsEntity = QueueDailyStatsEntity.builder()
                    .scopeType(SCOPE_TYPE)
                    .scopeId(SCOPE_ID)
                    .statDate(from)
                    .totalTickets((short) 100)
                    .completedCount((short) 80)
                    .build();
            DailyStatsResponse statsResponse = new DailyStatsResponse(
                    1L, "TEAM", SCOPE_ID, null, from,
                    (short) 100, (short) 80, (short) 10, (short) 5,
                    new BigDecimal("15.0"), new BigDecimal("10.0"),
                    (short) 14, (short) 30, (short) 70
            );

            given(dailyStatsRepository.findByScopeTypeAndScopeIdAndStatDateBetweenOrderByStatDateAsc(
                    SCOPE_TYPE, SCOPE_ID, from, to))
                    .willReturn(List.of(statsEntity));
            given(queueMapper.toDailyStatsResponseList(List.of(statsEntity)))
                    .willReturn(List.of(statsResponse));

            // When
            List<DailyStatsResponse> result = queueStatsService.getDailyStats(SCOPE_TYPE, SCOPE_ID, from, to);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTotalTickets()).isEqualTo((short) 100);
        }
    }

    // ========================================
    // getCounterDailyStats
    // ========================================

    @Nested
    @DisplayName("getCounterDailyStats")
    class GetCounterDailyStats {

        @Test
        @DisplayName("カウンター日次統計取得_正常_リスト返却")
        void カウンター日次統計取得_正常_リスト返却() {
            // Given
            LocalDate from = LocalDate.of(2026, 3, 1);
            LocalDate to = LocalDate.of(2026, 3, 25);

            given(dailyStatsRepository.findByCounterIdAndStatDateBetweenOrderByStatDateAsc(
                    COUNTER_ID, from, to))
                    .willReturn(List.of());
            given(queueMapper.toDailyStatsResponseList(List.of())).willReturn(List.of());

            // When
            List<DailyStatsResponse> result = queueStatsService.getCounterDailyStats(COUNTER_ID, from, to);

            // Then
            assertThat(result).isEmpty();
        }
    }
}
