package com.mannschaft.app.reservation;

import com.mannschaft.app.reservation.dto.BlockedTimeResponse;
import com.mannschaft.app.reservation.dto.BusinessHourResponse;
import com.mannschaft.app.reservation.dto.ReminderResponse;
import com.mannschaft.app.reservation.dto.ReservationLineResponse;
import com.mannschaft.app.reservation.dto.ReservationResponse;
import com.mannschaft.app.reservation.dto.ReservationSlotResponse;
import com.mannschaft.app.reservation.entity.ReservationBlockedTimeEntity;
import com.mannschaft.app.reservation.entity.ReservationBusinessHourEntity;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationLineEntity;
import com.mannschaft.app.reservation.entity.ReservationReminderEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReservationMapper}（MapStruct生成実装）の単体テスト。
 * Entity → DTO の変換ロジックを検証する。
 */
@DisplayName("ReservationMapper 単体テスト")
class ReservationMapperTest {

    private ReservationMapper reservationMapper;

    @BeforeEach
    void setUp() {
        reservationMapper = new ReservationMapperImpl();
    }

    // ========================================
    // toLineResponse
    // ========================================

    @Nested
    @DisplayName("toLineResponse")
    class ToLineResponse {

        @Test
        @DisplayName("正常系: ReservationLineEntityがReservationLineResponseに変換される")
        void toLineResponse_正常_DTOに変換() {
            // Given
            ReservationLineEntity entity = ReservationLineEntity.builder()
                    .teamId(10L)
                    .name("カット")
                    .description("ヘアカット")
                    .displayOrder(1)
                    .isActive(true)
                    .defaultStaffUserId(5L)
                    .build();
            ReflectionTestUtils.setField(entity, "id", 1L);

            // When
            ReservationLineResponse response = reservationMapper.toLineResponse(entity);

            // Then
            assertThat(response.getId()).isEqualTo(1L);
            assertThat(response.getTeamId()).isEqualTo(10L);
            assertThat(response.getName()).isEqualTo("カット");
            assertThat(response.getDescription()).isEqualTo("ヘアカット");
            assertThat(response.getIsActive()).isTrue();
            assertThat(response.getDefaultStaffUserId()).isEqualTo(5L);
        }

        @Test
        @DisplayName("正常系: 予約ラインリストが変換される")
        void toLineResponseList_正常_リスト変換() {
            // Given
            ReservationLineEntity e1 = ReservationLineEntity.builder()
                    .teamId(10L).name("カット").displayOrder(1).build();
            ReservationLineEntity e2 = ReservationLineEntity.builder()
                    .teamId(10L).name("カラー").displayOrder(2).build();
            ReflectionTestUtils.setField(e1, "id", 1L);
            ReflectionTestUtils.setField(e2, "id", 2L);

            // When
            List<ReservationLineResponse> responses = reservationMapper.toLineResponseList(List.of(e1, e2));

            // Then
            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).getName()).isEqualTo("カット");
            assertThat(responses.get(1).getName()).isEqualTo("カラー");
        }
    }

    // ========================================
    // toSlotResponse
    // ========================================

    @Nested
    @DisplayName("toSlotResponse")
    class ToSlotResponse {

        @Test
        @DisplayName("正常系: ReservationSlotEntityがReservationSlotResponseに変換される")
        void toSlotResponse_正常_DTOに変換() {
            // Given
            ReservationSlotEntity entity = ReservationSlotEntity.builder()
                    .teamId(10L)
                    .staffUserId(5L)
                    .title("午前の枠")
                    .slotDate(LocalDate.now().plusDays(1))
                    .startTime(LocalTime.of(10, 0))
                    .endTime(LocalTime.of(11, 0))
                    .bookedCount(2)
                    .slotStatus(SlotStatus.AVAILABLE)
                    .price(new BigDecimal("3000"))
                    .build();
            ReflectionTestUtils.setField(entity, "id", 20L);

            // When
            ReservationSlotResponse response = reservationMapper.toSlotResponse(entity);

            // Then
            assertThat(response.getId()).isEqualTo(20L);
            assertThat(response.getTeamId()).isEqualTo(10L);
            assertThat(response.getStaffUserId()).isEqualTo(5L);
            assertThat(response.getTitle()).isEqualTo("午前の枠");
            assertThat(response.getSlotStatus()).isEqualTo("AVAILABLE");
            assertThat(response.getBookedCount()).isEqualTo(2);
            assertThat(response.getPrice()).isEqualByComparingTo("3000");
        }

        @Test
        @DisplayName("正常系: FULL状態のスロットが変換される")
        void toSlotResponse_FULL状態_DTOに変換() {
            // Given
            ReservationSlotEntity entity = ReservationSlotEntity.builder()
                    .teamId(10L)
                    .slotDate(LocalDate.now().plusDays(2))
                    .startTime(LocalTime.of(14, 0))
                    .endTime(LocalTime.of(15, 0))
                    .bookedCount(5)
                    .slotStatus(SlotStatus.FULL)
                    .build();
            ReflectionTestUtils.setField(entity, "id", 21L);

            // When
            ReservationSlotResponse response = reservationMapper.toSlotResponse(entity);

            // Then
            assertThat(response.getSlotStatus()).isEqualTo("FULL");
        }

        @Test
        @DisplayName("正常系: スロットリストが変換される")
        void toSlotResponseList_正常_リスト変換() {
            // Given
            ReservationSlotEntity e1 = ReservationSlotEntity.builder()
                    .teamId(10L).slotDate(LocalDate.now())
                    .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(10, 0))
                    .slotStatus(SlotStatus.AVAILABLE).build();
            ReservationSlotEntity e2 = ReservationSlotEntity.builder()
                    .teamId(10L).slotDate(LocalDate.now())
                    .startTime(LocalTime.of(11, 0)).endTime(LocalTime.of(12, 0))
                    .slotStatus(SlotStatus.CLOSED).build();
            ReflectionTestUtils.setField(e1, "id", 1L);
            ReflectionTestUtils.setField(e2, "id", 2L);

            // When
            List<ReservationSlotResponse> responses = reservationMapper.toSlotResponseList(List.of(e1, e2));

            // Then
            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).getSlotStatus()).isEqualTo("AVAILABLE");
            assertThat(responses.get(1).getSlotStatus()).isEqualTo("CLOSED");
        }
    }

    // ========================================
    // toReservationResponse
    // ========================================

    @Nested
    @DisplayName("toReservationResponse")
    class ToReservationResponse {

        @Test
        @DisplayName("正常系: ReservationEntityがReservationResponseに変換される_PENDING")
        void toReservationResponse_PENDING_DTOに変換() {
            // Given
            ReservationEntity entity = ReservationEntity.builder()
                    .reservationSlotId(10L)
                    .lineId(20L)
                    .teamId(30L)
                    .userId(100L)
                    .status(ReservationStatus.PENDING)
                    .userNote("ご希望メモ")
                    .build();
            ReflectionTestUtils.setField(entity, "id", 50L);

            // When
            ReservationResponse response = reservationMapper.toReservationResponse(entity);

            // Then
            assertThat(response.getId()).isEqualTo(50L);
            assertThat(response.getReservationSlotId()).isEqualTo(10L);
            assertThat(response.getLineId()).isEqualTo(20L);
            assertThat(response.getTeamId()).isEqualTo(30L);
            assertThat(response.getUserId()).isEqualTo(100L);
            assertThat(response.getStatus()).isEqualTo("PENDING");
            assertThat(response.getCancelledBy()).isNull();
            assertThat(response.getUserNote()).isEqualTo("ご希望メモ");
        }

        @Test
        @DisplayName("正常系: キャンセル済み予約が変換される_cancelledByあり")
        void toReservationResponse_CANCELLED_cancelledByあり_DTOに変換() {
            // Given
            ReservationEntity entity = ReservationEntity.builder()
                    .reservationSlotId(10L)
                    .lineId(20L)
                    .teamId(30L)
                    .userId(100L)
                    .status(ReservationStatus.CANCELLED)
                    .cancelReason("都合が悪くなりました")
                    .cancelledBy(CancelledBy.USER)
                    .build();
            ReflectionTestUtils.setField(entity, "id", 51L);

            // When
            ReservationResponse response = reservationMapper.toReservationResponse(entity);

            // Then
            assertThat(response.getStatus()).isEqualTo("CANCELLED");
            assertThat(response.getCancelledBy()).isEqualTo("USER");
            assertThat(response.getCancelReason()).isEqualTo("都合が悪くなりました");
        }

        @Test
        @DisplayName("正常系: 管理者キャンセルがDTOに変換される")
        void toReservationResponse_TEAM_cancelledBy_DTOに変換() {
            // Given
            ReservationEntity entity = ReservationEntity.builder()
                    .reservationSlotId(10L)
                    .lineId(20L)
                    .teamId(30L)
                    .userId(100L)
                    .status(ReservationStatus.CANCELLED)
                    .cancelReason("店舗都合")
                    .cancelledBy(CancelledBy.ADMIN)
                    .build();
            ReflectionTestUtils.setField(entity, "id", 52L);

            // When
            ReservationResponse response = reservationMapper.toReservationResponse(entity);

            // Then
            assertThat(response.getCancelledBy()).isEqualTo("ADMIN");
        }

        @Test
        @DisplayName("正常系: 予約リストが変換される")
        void toReservationResponseList_正常_リスト変換() {
            // Given
            ReservationEntity e1 = ReservationEntity.builder()
                    .reservationSlotId(10L).lineId(20L).teamId(30L).userId(100L)
                    .status(ReservationStatus.PENDING).build();
            ReservationEntity e2 = ReservationEntity.builder()
                    .reservationSlotId(11L).lineId(20L).teamId(30L).userId(101L)
                    .status(ReservationStatus.CONFIRMED).build();
            ReflectionTestUtils.setField(e1, "id", 1L);
            ReflectionTestUtils.setField(e2, "id", 2L);

            // When
            List<ReservationResponse> responses = reservationMapper.toReservationResponseList(List.of(e1, e2));

            // Then
            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).getStatus()).isEqualTo("PENDING");
            assertThat(responses.get(1).getStatus()).isEqualTo("CONFIRMED");
        }
    }

    // ========================================
    // toBusinessHourResponse
    // ========================================

    @Nested
    @DisplayName("toBusinessHourResponse")
    class ToBusinessHourResponse {

        @Test
        @DisplayName("正常系: ReservationBusinessHourEntityがBusinessHourResponseに変換される")
        void toBusinessHourResponse_正常_DTOに変換() {
            // Given
            ReservationBusinessHourEntity entity = ReservationBusinessHourEntity.builder()
                    .teamId(10L)
                    .dayOfWeek("MON")
                    .isOpen(true)
                    .openTime(LocalTime.of(9, 0))
                    .closeTime(LocalTime.of(18, 0))
                    .build();
            ReflectionTestUtils.setField(entity, "id", 100L);

            // When
            BusinessHourResponse response = reservationMapper.toBusinessHourResponse(entity);

            // Then
            assertThat(response.getId()).isEqualTo(100L);
            assertThat(response.getTeamId()).isEqualTo(10L);
            assertThat(response.getDayOfWeek()).isEqualTo("MON");
            assertThat(response.getIsOpen()).isTrue();
            assertThat(response.getOpenTime()).isEqualTo(LocalTime.of(9, 0));
            assertThat(response.getCloseTime()).isEqualTo(LocalTime.of(18, 0));
        }

        @Test
        @DisplayName("正常系: 営業時間リストが変換される")
        void toBusinessHourResponseList_正常_リスト変換() {
            // Given
            ReservationBusinessHourEntity e1 = ReservationBusinessHourEntity.builder()
                    .teamId(10L).dayOfWeek("MON").isOpen(true)
                    .openTime(LocalTime.of(9, 0)).closeTime(LocalTime.of(18, 0)).build();
            ReservationBusinessHourEntity e2 = ReservationBusinessHourEntity.builder()
                    .teamId(10L).dayOfWeek("SUN").isOpen(false).build();
            ReflectionTestUtils.setField(e1, "id", 1L);
            ReflectionTestUtils.setField(e2, "id", 2L);

            // When
            List<BusinessHourResponse> responses = reservationMapper.toBusinessHourResponseList(List.of(e1, e2));

            // Then
            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).getDayOfWeek()).isEqualTo("MON");
            assertThat(responses.get(1).getIsOpen()).isFalse();
        }
    }

    // ========================================
    // toBlockedTimeResponse
    // ========================================

    @Nested
    @DisplayName("toBlockedTimeResponse")
    class ToBlockedTimeResponse {

        @Test
        @DisplayName("正常系: ReservationBlockedTimeEntityがBlockedTimeResponseに変換される")
        void toBlockedTimeResponse_正常_DTOに変換() {
            // Given
            ReservationBlockedTimeEntity entity = ReservationBlockedTimeEntity.builder()
                    .teamId(10L)
                    .blockedDate(LocalDate.now().plusDays(3))
                    .startTime(LocalTime.of(12, 0))
                    .endTime(LocalTime.of(13, 0))
                    .reason("昼休み")
                    .createdBy(1L)
                    .build();
            ReflectionTestUtils.setField(entity, "id", 200L);

            // When
            BlockedTimeResponse response = reservationMapper.toBlockedTimeResponse(entity);

            // Then
            assertThat(response.getId()).isEqualTo(200L);
            assertThat(response.getTeamId()).isEqualTo(10L);
            assertThat(response.getReason()).isEqualTo("昼休み");
            assertThat(response.getStartTime()).isEqualTo(LocalTime.of(12, 0));
        }

        @Test
        @DisplayName("正常系: ブロック時間リストが変換される")
        void toBlockedTimeResponseList_正常_リスト変換() {
            // Given
            ReservationBlockedTimeEntity e1 = ReservationBlockedTimeEntity.builder()
                    .teamId(10L).blockedDate(LocalDate.now())
                    .startTime(LocalTime.of(12, 0)).endTime(LocalTime.of(13, 0))
                    .reason("昼休み").build();
            ReservationBlockedTimeEntity e2 = ReservationBlockedTimeEntity.builder()
                    .teamId(10L).blockedDate(LocalDate.now().plusDays(1))
                    .reason("臨時休業").build();
            ReflectionTestUtils.setField(e1, "id", 1L);
            ReflectionTestUtils.setField(e2, "id", 2L);

            // When
            List<BlockedTimeResponse> responses = reservationMapper.toBlockedTimeResponseList(List.of(e1, e2));

            // Then
            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).getReason()).isEqualTo("昼休み");
            assertThat(responses.get(1).getReason()).isEqualTo("臨時休業");
        }
    }

    // ========================================
    // toReminderResponse
    // ========================================

    @Nested
    @DisplayName("toReminderResponse")
    class ToReminderResponse {

        @Test
        @DisplayName("正常系: ReservationReminderEntityがReminderResponseに変換される_PENDING")
        void toReminderResponse_PENDING_DTOに変換() {
            // Given
            ReservationReminderEntity entity = ReservationReminderEntity.builder()
                    .reservationId(50L)
                    .remindAt(LocalDateTime.now().plusHours(24))
                    .status(ReminderStatus.PENDING)
                    .build();
            ReflectionTestUtils.setField(entity, "id", 300L);

            // When
            ReminderResponse response = reservationMapper.toReminderResponse(entity);

            // Then
            assertThat(response.getId()).isEqualTo(300L);
            assertThat(response.getReservationId()).isEqualTo(50L);
            assertThat(response.getStatus()).isEqualTo("PENDING");
            assertThat(response.getSentAt()).isNull();
        }

        @Test
        @DisplayName("正常系: SENT状態のリマインダーが変換される")
        void toReminderResponse_SENT_DTOに変換() {
            // Given
            ReservationReminderEntity entity = ReservationReminderEntity.builder()
                    .reservationId(50L)
                    .remindAt(LocalDateTime.now().minusHours(1))
                    .status(ReminderStatus.SENT)
                    .build();
            entity.markSent();
            ReflectionTestUtils.setField(entity, "id", 301L);

            // When
            ReminderResponse response = reservationMapper.toReminderResponse(entity);

            // Then
            assertThat(response.getStatus()).isEqualTo("SENT");
            assertThat(response.getSentAt()).isNotNull();
        }

        @Test
        @DisplayName("正常系: リマインダーリストが変換される")
        void toReminderResponseList_正常_リスト変換() {
            // Given
            ReservationReminderEntity e1 = ReservationReminderEntity.builder()
                    .reservationId(50L).remindAt(LocalDateTime.now().plusDays(1))
                    .status(ReminderStatus.PENDING).build();
            ReservationReminderEntity e2 = ReservationReminderEntity.builder()
                    .reservationId(51L).remindAt(LocalDateTime.now().plusDays(2))
                    .status(ReminderStatus.CANCELLED).build();
            ReflectionTestUtils.setField(e1, "id", 1L);
            ReflectionTestUtils.setField(e2, "id", 2L);

            // When
            List<ReminderResponse> responses = reservationMapper.toReminderResponseList(List.of(e1, e2));

            // Then
            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).getStatus()).isEqualTo("PENDING");
            assertThat(responses.get(1).getStatus()).isEqualTo("CANCELLED");
        }
    }
}
