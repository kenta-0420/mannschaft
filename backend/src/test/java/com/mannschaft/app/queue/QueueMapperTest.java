package com.mannschaft.app.queue;

import com.mannschaft.app.queue.dto.CategoryResponse;
import com.mannschaft.app.queue.dto.CounterResponse;
import com.mannschaft.app.queue.dto.DailyStatsResponse;
import com.mannschaft.app.queue.dto.QrCodeResponse;
import com.mannschaft.app.queue.dto.SettingsResponse;
import com.mannschaft.app.queue.dto.TicketResponse;
import com.mannschaft.app.queue.entity.QueueCategoryEntity;
import com.mannschaft.app.queue.entity.QueueCounterEntity;
import com.mannschaft.app.queue.entity.QueueDailyStatsEntity;
import com.mannschaft.app.queue.entity.QueueQrCodeEntity;
import com.mannschaft.app.queue.entity.QueueSettingsEntity;
import com.mannschaft.app.queue.entity.QueueTicketEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link QueueMapper}（MapStruct生成実装）の単体テスト。
 * Entity → DTO の変換ロジックを検証する。
 */
@DisplayName("QueueMapper 単体テスト")
class QueueMapperTest {

    private QueueMapper queueMapper;

    @BeforeEach
    void setUp() {
        queueMapper = new QueueMapperImpl();
    }

    // ========================================
    // toCategoryResponse
    // ========================================

    @Nested
    @DisplayName("toCategoryResponse")
    class ToCategoryResponse {

        @Test
        @DisplayName("正常系: QueueCategoryEntityがCategoryResponseに変換される")
        void toCategoryResponse_正常_DTOに変換() {
            // Given
            QueueCategoryEntity entity = QueueCategoryEntity.builder()
                    .scopeType(QueueScopeType.TEAM)
                    .scopeId(10L)
                    .name("一般受付")
                    .queueMode(QueueMode.INDIVIDUAL)
                    .prefixChar("A")
                    .maxQueueSize((short) 50)
                    .displayOrder((short) 0)
                    .build();
            ReflectionTestUtils.setField(entity, "id", 1L);

            // When
            CategoryResponse response = queueMapper.toCategoryResponse(entity);

            // Then
            assertThat(response.getId()).isEqualTo(1L);
            assertThat(response.getScopeType()).isEqualTo("TEAM");
            assertThat(response.getScopeId()).isEqualTo(10L);
            assertThat(response.getName()).isEqualTo("一般受付");
            assertThat(response.getQueueMode()).isEqualTo("INDIVIDUAL");
            assertThat(response.getPrefixChar()).isEqualTo("A");
            assertThat(response.getMaxQueueSize()).isEqualTo((short) 50);
            assertThat(response.getDisplayOrder()).isEqualTo((short) 0);
        }

        @Test
        @DisplayName("正常系: SHAREDモードのカテゴリが変換される")
        void toCategoryResponse_SHAREDモード_DTOに変換() {
            // Given
            QueueCategoryEntity entity = QueueCategoryEntity.builder()
                    .scopeType(QueueScopeType.ORGANIZATION)
                    .scopeId(5L)
                    .name("VIP受付")
                    .queueMode(QueueMode.SHARED)
                    .prefixChar("V")
                    .maxQueueSize((short) 30)
                    .displayOrder((short) 1)
                    .build();
            ReflectionTestUtils.setField(entity, "id", 2L);

            // When
            CategoryResponse response = queueMapper.toCategoryResponse(entity);

            // Then
            assertThat(response.getScopeType()).isEqualTo("ORGANIZATION");
            assertThat(response.getQueueMode()).isEqualTo("SHARED");
        }

        @Test
        @DisplayName("正常系: カテゴリリストが変換される")
        void toCategoryResponseList_正常_リスト変換() {
            // Given
            QueueCategoryEntity e1 = QueueCategoryEntity.builder()
                    .scopeType(QueueScopeType.TEAM)
                    .scopeId(10L)
                    .name("受付A")
                    .queueMode(QueueMode.INDIVIDUAL)
                    .maxQueueSize((short) 50)
                    .displayOrder((short) 0)
                    .build();
            QueueCategoryEntity e2 = QueueCategoryEntity.builder()
                    .scopeType(QueueScopeType.TEAM)
                    .scopeId(10L)
                    .name("受付B")
                    .queueMode(QueueMode.SHARED)
                    .maxQueueSize((short) 30)
                    .displayOrder((short) 1)
                    .build();
            ReflectionTestUtils.setField(e1, "id", 1L);
            ReflectionTestUtils.setField(e2, "id", 2L);

            // When
            List<CategoryResponse> responses = queueMapper.toCategoryResponseList(List.of(e1, e2));

            // Then
            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).getName()).isEqualTo("受付A");
            assertThat(responses.get(1).getName()).isEqualTo("受付B");
        }
    }

    // ========================================
    // toCounterResponse
    // ========================================

    @Nested
    @DisplayName("toCounterResponse")
    class ToCounterResponse {

        @Test
        @DisplayName("正常系: QueueCounterEntityがCounterResponseに変換される")
        void toCounterResponse_正常_DTOに変換() {
            // Given
            QueueCounterEntity entity = QueueCounterEntity.builder()
                    .categoryId(5L)
                    .name("窓口1")
                    .description("通常窓口")
                    .acceptMode(AcceptMode.BOTH)
                    .avgServiceMinutes((short) 10)
                    .avgServiceMinutesManual(false)
                    .maxQueueSize((short) 50)
                    .isActive(true)
                    .isAccepting(true)
                    .operatingTimeFrom(LocalTime.of(9, 0))
                    .operatingTimeTo(LocalTime.of(18, 0))
                    .displayOrder((short) 0)
                    .build();
            ReflectionTestUtils.setField(entity, "id", 10L);

            // When
            CounterResponse response = queueMapper.toCounterResponse(entity);

            // Then
            assertThat(response.getId()).isEqualTo(10L);
            assertThat(response.getCategoryId()).isEqualTo(5L);
            assertThat(response.getName()).isEqualTo("窓口1");
            assertThat(response.getAcceptMode()).isEqualTo("BOTH");
            assertThat(response.getIsActive()).isTrue();
            assertThat(response.getIsAccepting()).isTrue();
        }

        @Test
        @DisplayName("正常系: カウンターリストが変換される")
        void toCounterResponseList_正常_リスト変換() {
            // Given
            QueueCounterEntity e1 = QueueCounterEntity.builder()
                    .categoryId(5L)
                    .name("窓口1")
                    .acceptMode(AcceptMode.BOTH)
                    .avgServiceMinutes((short) 10)
                    .maxQueueSize((short) 50)
                    .build();
            QueueCounterEntity e2 = QueueCounterEntity.builder()
                    .categoryId(5L)
                    .name("窓口2")
                    .acceptMode(AcceptMode.QR_ONLY)
                    .avgServiceMinutes((short) 8)
                    .maxQueueSize((short) 30)
                    .build();
            ReflectionTestUtils.setField(e1, "id", 1L);
            ReflectionTestUtils.setField(e2, "id", 2L);

            // When
            List<CounterResponse> responses = queueMapper.toCounterResponseList(List.of(e1, e2));

            // Then
            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).getAcceptMode()).isEqualTo("BOTH");
            assertThat(responses.get(1).getAcceptMode()).isEqualTo("QR_ONLY");
        }
    }

    // ========================================
    // toTicketResponse
    // ========================================

    @Nested
    @DisplayName("toTicketResponse")
    class ToTicketResponse {

        @Test
        @DisplayName("正常系: QueueTicketEntityがTicketResponseに変換される")
        void toTicketResponse_正常_DTOに変換() {
            // Given
            QueueTicketEntity entity = QueueTicketEntity.builder()
                    .categoryId(5L)
                    .counterId(10L)
                    .ticketNumber("Q001")
                    .userId(100L)
                    .partySize((short) 2)
                    .source(TicketSource.ONLINE)
                    .status(TicketStatus.WAITING)
                    .position(1)
                    .estimatedWaitMinutes((short) 20)
                    .issuedDate(LocalDate.now())
                    .build();
            ReflectionTestUtils.setField(entity, "id", 50L);

            // When
            TicketResponse response = queueMapper.toTicketResponse(entity);

            // Then
            assertThat(response.getId()).isEqualTo(50L);
            assertThat(response.getCategoryId()).isEqualTo(5L);
            assertThat(response.getCounterId()).isEqualTo(10L);
            assertThat(response.getTicketNumber()).isEqualTo("Q001");
            assertThat(response.getUserId()).isEqualTo(100L);
            assertThat(response.getSource()).isEqualTo("ONLINE");
            assertThat(response.getStatus()).isEqualTo("WAITING");
            assertThat(response.getPosition()).isEqualTo(1);
        }

        @Test
        @DisplayName("正常系: QRソースのチケットが変換される")
        void toTicketResponse_QRソース_DTOに変換() {
            // Given
            QueueTicketEntity entity = QueueTicketEntity.builder()
                    .categoryId(5L)
                    .counterId(10L)
                    .ticketNumber("Q002")
                    .partySize((short) 1)
                    .source(TicketSource.QR)
                    .status(TicketStatus.CALLED)
                    .position(2)
                    .issuedDate(LocalDate.now())
                    .build();
            ReflectionTestUtils.setField(entity, "id", 51L);

            // When
            TicketResponse response = queueMapper.toTicketResponse(entity);

            // Then
            assertThat(response.getSource()).isEqualTo("QR");
            assertThat(response.getStatus()).isEqualTo("CALLED");
        }

        @Test
        @DisplayName("正常系: チケットリストが変換される")
        void toTicketResponseList_正常_リスト変換() {
            // Given
            QueueTicketEntity e1 = QueueTicketEntity.builder()
                    .categoryId(5L).counterId(10L)
                    .ticketNumber("Q001")
                    .partySize((short) 1)
                    .source(TicketSource.ONLINE)
                    .status(TicketStatus.WAITING)
                    .position(1)
                    .issuedDate(LocalDate.now())
                    .build();
            QueueTicketEntity e2 = QueueTicketEntity.builder()
                    .categoryId(5L).counterId(10L)
                    .ticketNumber("Q002")
                    .partySize((short) 1)
                    .source(TicketSource.ADMIN)
                    .status(TicketStatus.SERVING)
                    .position(2)
                    .issuedDate(LocalDate.now())
                    .build();
            ReflectionTestUtils.setField(e1, "id", 1L);
            ReflectionTestUtils.setField(e2, "id", 2L);

            // When
            List<TicketResponse> responses = queueMapper.toTicketResponseList(List.of(e1, e2));

            // Then
            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).getStatus()).isEqualTo("WAITING");
            assertThat(responses.get(1).getSource()).isEqualTo("ADMIN");
        }
    }

    // ========================================
    // toSettingsResponse
    // ========================================

    @Nested
    @DisplayName("toSettingsResponse")
    class ToSettingsResponse {

        @Test
        @DisplayName("正常系: QueueSettingsEntityがSettingsResponseに変換される")
        void toSettingsResponse_正常_DTOに変換() {
            // Given
            QueueSettingsEntity entity = QueueSettingsEntity.builder()
                    .scopeType(QueueScopeType.TEAM)
                    .scopeId(10L)
                    .noShowTimeoutMinutes((short) 5)
                    .noShowPenaltyEnabled(false)
                    .noShowPenaltyThreshold((short) 3)
                    .noShowPenaltyDays((short) 14)
                    .maxActiveTicketsPerUser((short) 1)
                    .allowGuestQueue(true)
                    .almostReadyThreshold((short) 3)
                    .holdExtensionMinutes((short) 5)
                    .autoAdjustServiceMinutes(false)
                    .displayBoardPublic(false)
                    .build();
            ReflectionTestUtils.setField(entity, "id", 20L);

            // When
            SettingsResponse response = queueMapper.toSettingsResponse(entity);

            // Then
            assertThat(response.getId()).isEqualTo(20L);
            assertThat(response.getScopeType()).isEqualTo("TEAM");
            assertThat(response.getScopeId()).isEqualTo(10L);
            assertThat(response.getAllowGuestQueue()).isTrue();
            assertThat(response.getMaxActiveTicketsPerUser()).isEqualTo((short) 1);
        }
    }

    // ========================================
    // toQrCodeResponse
    // ========================================

    @Nested
    @DisplayName("toQrCodeResponse")
    class ToQrCodeResponse {

        @Test
        @DisplayName("正常系: QueueQrCodeEntityがQrCodeResponseに変換される")
        void toQrCodeResponse_正常_DTOに変換() {
            // Given
            QueueQrCodeEntity entity = QueueQrCodeEntity.builder()
                    .categoryId(5L)
                    .counterId(10L)
                    .qrToken("abc123token")
                    .isActive(true)
                    .build();
            ReflectionTestUtils.setField(entity, "id", 30L);

            // When
            QrCodeResponse response = queueMapper.toQrCodeResponse(entity);

            // Then
            assertThat(response.getId()).isEqualTo(30L);
            assertThat(response.getCategoryId()).isEqualTo(5L);
            assertThat(response.getCounterId()).isEqualTo(10L);
            assertThat(response.getQrToken()).isEqualTo("abc123token");
            assertThat(response.getIsActive()).isTrue();
        }

        @Test
        @DisplayName("正常系: QRコードリストが変換される")
        void toQrCodeResponseList_正常_リスト変換() {
            // Given
            QueueQrCodeEntity e1 = QueueQrCodeEntity.builder()
                    .categoryId(5L).qrToken("token1").isActive(true).build();
            QueueQrCodeEntity e2 = QueueQrCodeEntity.builder()
                    .categoryId(5L).qrToken("token2").isActive(false).build();
            ReflectionTestUtils.setField(e1, "id", 1L);
            ReflectionTestUtils.setField(e2, "id", 2L);

            // When
            List<QrCodeResponse> responses = queueMapper.toQrCodeResponseList(List.of(e1, e2));

            // Then
            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).getQrToken()).isEqualTo("token1");
            assertThat(responses.get(1).getIsActive()).isFalse();
        }
    }

    // ========================================
    // toDailyStatsResponse
    // ========================================

    @Nested
    @DisplayName("toDailyStatsResponse")
    class ToDailyStatsResponse {

        @Test
        @DisplayName("正常系: QueueDailyStatsEntityがDailyStatsResponseに変換される")
        void toDailyStatsResponse_正常_DTOに変換() {
            // Given
            QueueDailyStatsEntity entity = QueueDailyStatsEntity.builder()
                    .scopeType(QueueScopeType.TEAM)
                    .scopeId(10L)
                    .counterId(20L)
                    .statDate(LocalDate.now())
                    .totalTickets((short) 100)
                    .completedCount((short) 80)
                    .cancelledCount((short) 10)
                    .noShowCount((short) 5)
                    .avgWaitMinutes(new BigDecimal("15.5"))
                    .avgServiceMinutes(new BigDecimal("10.2"))
                    .peakHour((short) 10)
                    .qrCount((short) 60)
                    .onlineCount((short) 40)
                    .build();
            ReflectionTestUtils.setField(entity, "id", 40L);

            // When
            DailyStatsResponse response = queueMapper.toDailyStatsResponse(entity);

            // Then
            assertThat(response.getId()).isEqualTo(40L);
            assertThat(response.getScopeType()).isEqualTo("TEAM");
            assertThat(response.getScopeId()).isEqualTo(10L);
            assertThat(response.getTotalTickets()).isEqualTo((short) 100);
            assertThat(response.getCompletedCount()).isEqualTo((short) 80);
            assertThat(response.getAvgWaitMinutes()).isEqualByComparingTo("15.5");
        }

        @Test
        @DisplayName("正常系: 日次統計リストが変換される")
        void toDailyStatsResponseList_正常_リスト変換() {
            // Given
            QueueDailyStatsEntity e1 = QueueDailyStatsEntity.builder()
                    .scopeType(QueueScopeType.TEAM)
                    .scopeId(10L)
                    .statDate(LocalDate.now())
                    .totalTickets((short) 50)
                    .completedCount((short) 40)
                    .cancelledCount((short) 5)
                    .noShowCount((short) 2)
                    .qrCount((short) 20)
                    .onlineCount((short) 30)
                    .build();
            QueueDailyStatsEntity e2 = QueueDailyStatsEntity.builder()
                    .scopeType(QueueScopeType.TEAM)
                    .scopeId(10L)
                    .statDate(LocalDate.now().minusDays(1))
                    .totalTickets((short) 80)
                    .completedCount((short) 65)
                    .cancelledCount((short) 8)
                    .noShowCount((short) 3)
                    .qrCount((short) 50)
                    .onlineCount((short) 30)
                    .build();
            ReflectionTestUtils.setField(e1, "id", 1L);
            ReflectionTestUtils.setField(e2, "id", 2L);

            // When
            List<DailyStatsResponse> responses = queueMapper.toDailyStatsResponseList(List.of(e1, e2));

            // Then
            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).getTotalTickets()).isEqualTo((short) 50);
            assertThat(responses.get(1).getTotalTickets()).isEqualTo((short) 80);
        }
    }
}
