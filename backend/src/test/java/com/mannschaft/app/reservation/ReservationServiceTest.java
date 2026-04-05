package com.mannschaft.app.reservation;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reservation.dto.AdminNoteRequest;
import com.mannschaft.app.reservation.dto.CancelReservationRequest;
import com.mannschaft.app.reservation.dto.CreateReservationRequest;
import com.mannschaft.app.reservation.dto.RescheduleRequest;
import com.mannschaft.app.reservation.dto.ReservationResponse;
import com.mannschaft.app.reservation.dto.ReservationStatsResponse;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.service.ReservationService;
import com.mannschaft.app.reservation.service.ReservationSlotService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ReservationService} の単体テスト。
 * 予約のCRUD・ステータス遷移・統計を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationService 単体テスト")
class ReservationServiceTest {

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationSlotService slotService;

    @Mock
    private ReservationMapper reservationMapper;

    @InjectMocks
    private ReservationService service;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long TEAM_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long RESERVATION_ID = 10L;
    private static final Long SLOT_ID = 20L;
    private static final Long LINE_ID = 30L;

    private ReservationEntity createReservationEntity() {
        return ReservationEntity.builder()
                .reservationSlotId(SLOT_ID)
                .lineId(LINE_ID)
                .teamId(TEAM_ID)
                .userId(USER_ID)
                .userNote("テスト備考")
                .build();
    }

    private ReservationEntity createConfirmedReservationEntity() {
        ReservationEntity entity = createReservationEntity();
        entity.confirm();
        return entity;
    }

    private ReservationSlotEntity createAvailableSlotEntity() {
        return ReservationSlotEntity.builder()
                .teamId(TEAM_ID)
                .slotDate(java.time.LocalDate.of(2026, 4, 1))
                .startTime(java.time.LocalTime.of(10, 0))
                .endTime(java.time.LocalTime.of(11, 0))
                .build();
    }

    private ReservationSlotEntity createFullSlotEntity() {
        ReservationSlotEntity entity = createAvailableSlotEntity();
        entity.markFull();
        return entity;
    }

    private ReservationSlotEntity createClosedSlotEntity() {
        ReservationSlotEntity entity = createAvailableSlotEntity();
        entity.close("メンテナンス");
        return entity;
    }

    private ReservationResponse createReservationResponse() {
        return new ReservationResponse(
                RESERVATION_ID, SLOT_ID, LINE_ID, TEAM_ID, USER_ID,
                "PENDING", LocalDateTime.now(), null, null, null, null,
                null, "テスト備考", null, null, null);
    }

    // ========================================
    // listTeamReservations
    // ========================================

    @Nested
    @DisplayName("listTeamReservations")
    class ListTeamReservations {

        @Test
        @DisplayName("正常系: ステータスフィルタなしで全予約を返却する")
        void 全予約一覧_ステータスなし() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            ReservationEntity entity = createReservationEntity();
            Page<ReservationEntity> page = new PageImpl<>(List.of(entity));
            ReservationResponse response = createReservationResponse();
            given(reservationRepository.findByTeamIdOrderByBookedAtDesc(TEAM_ID, pageable)).willReturn(page);
            given(reservationMapper.toReservationResponse(entity)).willReturn(response);

            // When
            Page<ReservationResponse> result = service.listTeamReservations(TEAM_ID, null, pageable);

            // Then
            assertThat(result.getContent()).hasSize(1);
            verify(reservationRepository).findByTeamIdOrderByBookedAtDesc(TEAM_ID, pageable);
        }

        @Test
        @DisplayName("正常系: ステータスフィルタありで予約を返却する")
        void 全予約一覧_ステータスあり() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            ReservationEntity entity = createReservationEntity();
            Page<ReservationEntity> page = new PageImpl<>(List.of(entity));
            ReservationResponse response = createReservationResponse();
            given(reservationRepository.findByTeamIdAndStatusOrderByBookedAtDesc(
                    TEAM_ID, ReservationStatus.PENDING, pageable)).willReturn(page);
            given(reservationMapper.toReservationResponse(entity)).willReturn(response);

            // When
            Page<ReservationResponse> result = service.listTeamReservations(TEAM_ID, "PENDING", pageable);

            // Then
            assertThat(result.getContent()).hasSize(1);
            verify(reservationRepository).findByTeamIdAndStatusOrderByBookedAtDesc(
                    TEAM_ID, ReservationStatus.PENDING, pageable);
        }
    }

    // ========================================
    // getReservation
    // ========================================

    @Nested
    @DisplayName("getReservation")
    class GetReservation {

        @Test
        @DisplayName("正常系: 予約詳細が返却される")
        void 予約詳細_正常取得() {
            // Given
            ReservationEntity entity = createReservationEntity();
            ReservationResponse response = createReservationResponse();
            given(reservationRepository.findByIdAndTeamId(RESERVATION_ID, TEAM_ID))
                    .willReturn(Optional.of(entity));
            given(reservationMapper.toReservationResponse(entity)).willReturn(response);

            // When
            ReservationResponse result = service.getReservation(TEAM_ID, RESERVATION_ID);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getTeamId()).isEqualTo(TEAM_ID);
        }

        @Test
        @DisplayName("異常系: 予約が存在しない場合BusinessExceptionがスローされる")
        void 予約詳細_存在しない() {
            // Given
            given(reservationRepository.findByIdAndTeamId(RESERVATION_ID, TEAM_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.getReservation(TEAM_ID, RESERVATION_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.RESERVATION_NOT_FOUND);
        }
    }

    // ========================================
    // createReservation
    // ========================================

    @Nested
    @DisplayName("createReservation")
    class CreateReservation {

        @Test
        @DisplayName("正常系: 予約が作成される")
        void 予約作成_正常() {
            // Given
            CreateReservationRequest request = new CreateReservationRequest(SLOT_ID, LINE_ID, "テスト備考");
            ReservationSlotEntity slot = createAvailableSlotEntity();
            ReservationEntity savedEntity = createReservationEntity();
            ReservationResponse response = createReservationResponse();

            given(slotService.getSlotEntity(SLOT_ID)).willReturn(slot);
            given(reservationRepository.existsByReservationSlotIdAndUserIdAndStatusIn(
                    eq(SLOT_ID), eq(USER_ID), any())).willReturn(false);
            given(reservationRepository.save(any(ReservationEntity.class))).willReturn(savedEntity);
            given(reservationMapper.toReservationResponse(savedEntity)).willReturn(response);

            // When
            ReservationResponse result = service.createReservation(TEAM_ID, USER_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(reservationRepository).save(any(ReservationEntity.class));
            verify(slotService).incrementAndCheckFull(slot);
        }

        @Test
        @DisplayName("異常系: スロットが満席の場合SLOT_FULLエラー")
        void 予約作成_スロット満席() {
            // Given
            CreateReservationRequest request = new CreateReservationRequest(SLOT_ID, LINE_ID, null);
            ReservationSlotEntity slot = createFullSlotEntity();
            given(slotService.getSlotEntity(SLOT_ID)).willReturn(slot);

            // When / Then
            assertThatThrownBy(() -> service.createReservation(TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.SLOT_FULL);
        }

        @Test
        @DisplayName("異常系: スロットがクローズ済みの場合SLOT_CLOSEDエラー")
        void 予約作成_スロットクローズ() {
            // Given
            CreateReservationRequest request = new CreateReservationRequest(SLOT_ID, LINE_ID, null);
            ReservationSlotEntity slot = createClosedSlotEntity();
            given(slotService.getSlotEntity(SLOT_ID)).willReturn(slot);

            // When / Then
            assertThatThrownBy(() -> service.createReservation(TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.SLOT_CLOSED);
        }

        @Test
        @DisplayName("異常系: 重複予約の場合DUPLICATE_RESERVATIONエラー")
        void 予約作成_重複() {
            // Given
            CreateReservationRequest request = new CreateReservationRequest(SLOT_ID, LINE_ID, null);
            ReservationSlotEntity slot = createAvailableSlotEntity();
            given(slotService.getSlotEntity(SLOT_ID)).willReturn(slot);
            given(reservationRepository.existsByReservationSlotIdAndUserIdAndStatusIn(
                    eq(SLOT_ID), eq(USER_ID), any())).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> service.createReservation(TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.DUPLICATE_RESERVATION);
        }
    }

    // ========================================
    // confirmReservation
    // ========================================

    @Nested
    @DisplayName("confirmReservation")
    class ConfirmReservation {

        @Test
        @DisplayName("正常系: PENDING予約が確定される")
        void 予約確定_正常() {
            // Given
            ReservationEntity entity = createReservationEntity();
            ReservationResponse response = createReservationResponse();
            given(reservationRepository.findByIdAndTeamId(RESERVATION_ID, TEAM_ID))
                    .willReturn(Optional.of(entity));
            given(reservationRepository.save(entity)).willReturn(entity);
            given(reservationMapper.toReservationResponse(entity)).willReturn(response);

            // When
            ReservationResponse result = service.confirmReservation(TEAM_ID, RESERVATION_ID);

            // Then
            assertThat(result).isNotNull();
            verify(reservationRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: CONFIRMED予約を確定しようとするとINVALID_RESERVATION_STATUSエラー")
        void 予約確定_ステータス不正() {
            // Given
            ReservationEntity entity = createConfirmedReservationEntity();
            given(reservationRepository.findByIdAndTeamId(RESERVATION_ID, TEAM_ID))
                    .willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> service.confirmReservation(TEAM_ID, RESERVATION_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_RESERVATION_STATUS);
        }
    }

    // ========================================
    // cancelByAdmin
    // ========================================

    @Nested
    @DisplayName("cancelByAdmin")
    class CancelByAdmin {

        @Test
        @DisplayName("正常系: 管理者が予約をキャンセルする")
        void 管理者キャンセル_正常() {
            // Given
            CancelReservationRequest request = new CancelReservationRequest("管理者都合");
            ReservationEntity entity = createReservationEntity();
            ReservationSlotEntity slot = createAvailableSlotEntity();
            ReservationResponse response = createReservationResponse();

            given(reservationRepository.findByIdAndTeamId(RESERVATION_ID, TEAM_ID))
                    .willReturn(Optional.of(entity));
            given(reservationRepository.save(entity)).willReturn(entity);
            given(slotService.getSlotEntity(SLOT_ID)).willReturn(slot);
            given(reservationMapper.toReservationResponse(entity)).willReturn(response);

            // When
            ReservationResponse result = service.cancelByAdmin(TEAM_ID, RESERVATION_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(slotService).decrementAndReopen(slot);
        }

        @Test
        @DisplayName("異常系: COMPLETED予約をキャンセルしようとするとINVALID_RESERVATION_STATUSエラー")
        void 管理者キャンセル_ステータス不正() {
            // Given
            CancelReservationRequest request = new CancelReservationRequest("理由");
            ReservationEntity entity = createReservationEntity();
            entity.complete();
            given(reservationRepository.findByIdAndTeamId(RESERVATION_ID, TEAM_ID))
                    .willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> service.cancelByAdmin(TEAM_ID, RESERVATION_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_RESERVATION_STATUS);
        }
    }

    // ========================================
    // cancelByUser
    // ========================================

    @Nested
    @DisplayName("cancelByUser")
    class CancelByUser {

        @Test
        @DisplayName("正常系: ユーザーが予約をキャンセルする")
        void ユーザーキャンセル_正常() {
            // Given
            CancelReservationRequest request = new CancelReservationRequest("ユーザー都合");
            ReservationEntity entity = createReservationEntity();
            ReservationSlotEntity slot = createAvailableSlotEntity();
            ReservationResponse response = createReservationResponse();

            given(reservationRepository.findByIdAndUserId(RESERVATION_ID, USER_ID))
                    .willReturn(Optional.of(entity));
            given(reservationRepository.save(entity)).willReturn(entity);
            given(slotService.getSlotEntity(SLOT_ID)).willReturn(slot);
            given(reservationMapper.toReservationResponse(entity)).willReturn(response);

            // When
            ReservationResponse result = service.cancelByUser(USER_ID, RESERVATION_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(slotService).decrementAndReopen(slot);
        }

        @Test
        @DisplayName("異常系: 予約が存在しない場合RESERVATION_NOT_FOUNDエラー")
        void ユーザーキャンセル_予約なし() {
            // Given
            CancelReservationRequest request = new CancelReservationRequest("理由");
            given(reservationRepository.findByIdAndUserId(RESERVATION_ID, USER_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.cancelByUser(USER_ID, RESERVATION_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.RESERVATION_NOT_FOUND);
        }

        @Test
        @DisplayName("異常系: キャンセル不可のステータスの場合INVALID_RESERVATION_STATUSエラー")
        void ユーザーキャンセル_ステータス不正() {
            // Given
            CancelReservationRequest request = new CancelReservationRequest("理由");
            ReservationEntity entity = createReservationEntity();
            entity.complete();
            given(reservationRepository.findByIdAndUserId(RESERVATION_ID, USER_ID))
                    .willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> service.cancelByUser(USER_ID, RESERVATION_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_RESERVATION_STATUS);
        }
    }

    // ========================================
    // completeReservation
    // ========================================

    @Nested
    @DisplayName("completeReservation")
    class CompleteReservation {

        @Test
        @DisplayName("正常系: 予約を完了にする")
        void 予約完了_正常() {
            // Given
            ReservationEntity entity = createReservationEntity();
            ReservationResponse response = createReservationResponse();
            given(reservationRepository.findByIdAndTeamId(RESERVATION_ID, TEAM_ID))
                    .willReturn(Optional.of(entity));
            given(reservationRepository.save(entity)).willReturn(entity);
            given(reservationMapper.toReservationResponse(entity)).willReturn(response);

            // When
            ReservationResponse result = service.completeReservation(TEAM_ID, RESERVATION_ID);

            // Then
            assertThat(result).isNotNull();
            verify(reservationRepository).save(entity);
        }
    }

    // ========================================
    // markNoShow
    // ========================================

    @Nested
    @DisplayName("markNoShow")
    class MarkNoShow {

        @Test
        @DisplayName("正常系: ノーショーとしてマークする")
        void ノーショー_正常() {
            // Given
            ReservationEntity entity = createReservationEntity();
            ReservationResponse response = createReservationResponse();
            given(reservationRepository.findByIdAndTeamId(RESERVATION_ID, TEAM_ID))
                    .willReturn(Optional.of(entity));
            given(reservationRepository.save(entity)).willReturn(entity);
            given(reservationMapper.toReservationResponse(entity)).willReturn(response);

            // When
            ReservationResponse result = service.markNoShow(TEAM_ID, RESERVATION_ID);

            // Then
            assertThat(result).isNotNull();
            verify(reservationRepository).save(entity);
        }
    }

    // ========================================
    // rescheduleReservation
    // ========================================

    @Nested
    @DisplayName("rescheduleReservation")
    class RescheduleReservation {

        private static final Long NEW_SLOT_ID = 50L;

        @Test
        @DisplayName("正常系: 予約をリスケジュールする")
        void リスケジュール_正常() {
            // Given
            RescheduleRequest request = new RescheduleRequest(NEW_SLOT_ID);
            ReservationEntity entity = createReservationEntity();
            ReservationSlotEntity oldSlot = createAvailableSlotEntity();
            ReservationSlotEntity newSlot = createAvailableSlotEntity();
            ReservationResponse response = createReservationResponse();

            given(reservationRepository.findByIdAndTeamId(RESERVATION_ID, TEAM_ID))
                    .willReturn(Optional.of(entity));
            given(slotService.getSlotEntity(SLOT_ID)).willReturn(oldSlot);
            given(slotService.getSlotEntity(NEW_SLOT_ID)).willReturn(newSlot);
            given(reservationRepository.save(entity)).willReturn(entity);
            given(reservationMapper.toReservationResponse(entity)).willReturn(response);

            // When
            ReservationResponse result = service.rescheduleReservation(TEAM_ID, RESERVATION_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(slotService).decrementAndReopen(oldSlot);
            verify(slotService).incrementAndCheckFull(newSlot);
        }

        @Test
        @DisplayName("異常系: 新スロットが満席の場合SLOT_FULLエラー")
        void リスケジュール_新スロット満席() {
            // Given
            RescheduleRequest request = new RescheduleRequest(NEW_SLOT_ID);
            ReservationEntity entity = createReservationEntity();
            ReservationSlotEntity oldSlot = createAvailableSlotEntity();
            ReservationSlotEntity newSlot = createFullSlotEntity();

            given(reservationRepository.findByIdAndTeamId(RESERVATION_ID, TEAM_ID))
                    .willReturn(Optional.of(entity));
            given(slotService.getSlotEntity(SLOT_ID)).willReturn(oldSlot);
            given(slotService.getSlotEntity(NEW_SLOT_ID)).willReturn(newSlot);

            // When / Then
            assertThatThrownBy(() -> service.rescheduleReservation(TEAM_ID, RESERVATION_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.SLOT_FULL);
        }
    }

    // ========================================
    // updateAdminNote
    // ========================================

    @Nested
    @DisplayName("updateAdminNote")
    class UpdateAdminNote {

        @Test
        @DisplayName("正常系: 管理者メモが更新される")
        void 管理者メモ更新_正常() {
            // Given
            AdminNoteRequest request = new AdminNoteRequest("管理者メモ内容");
            ReservationEntity entity = createReservationEntity();
            ReservationResponse response = createReservationResponse();
            given(reservationRepository.findByIdAndTeamId(RESERVATION_ID, TEAM_ID))
                    .willReturn(Optional.of(entity));
            given(reservationRepository.save(entity)).willReturn(entity);
            given(reservationMapper.toReservationResponse(entity)).willReturn(response);

            // When
            ReservationResponse result = service.updateAdminNote(TEAM_ID, RESERVATION_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(reservationRepository).save(entity);
        }
    }

    // ========================================
    // listReservationsBySlot
    // ========================================

    @Nested
    @DisplayName("listReservationsBySlot")
    class ListReservationsBySlot {

        @Test
        @DisplayName("正常系: スロットに紐付く予約一覧が返却される")
        void スロット別予約一覧_正常() {
            // Given
            List<ReservationEntity> entities = List.of(createReservationEntity());
            List<ReservationResponse> responses = List.of(createReservationResponse());
            given(reservationRepository.findByReservationSlotIdOrderByBookedAtAsc(SLOT_ID)).willReturn(entities);
            given(reservationMapper.toReservationResponseList(entities)).willReturn(responses);

            // When
            List<ReservationResponse> result = service.listReservationsBySlot(SLOT_ID);

            // Then
            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // listMyReservations
    // ========================================

    @Nested
    @DisplayName("listMyReservations")
    class ListMyReservations {

        @Test
        @DisplayName("正常系: ユーザーの予約一覧が返却される")
        void マイ予約一覧_正常() {
            // Given
            List<ReservationEntity> entities = List.of(createReservationEntity());
            List<ReservationResponse> responses = List.of(createReservationResponse());
            given(reservationRepository.findByUserIdOrderByBookedAtDesc(USER_ID)).willReturn(entities);
            given(reservationMapper.toReservationResponseList(entities)).willReturn(responses);

            // When
            List<ReservationResponse> result = service.listMyReservations(USER_ID);

            // Then
            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // listUpcomingReservations
    // ========================================

    @Nested
    @DisplayName("listUpcomingReservations")
    class ListUpcomingReservations {

        @Test
        @DisplayName("正常系: 直近の予約一覧が返却される")
        void 直近予約一覧_正常() {
            // Given
            List<ReservationEntity> entities = List.of(createReservationEntity());
            List<ReservationResponse> responses = List.of(createReservationResponse());
            given(reservationRepository.findUpcomingByUserId(eq(USER_ID), any(LocalDateTime.class)))
                    .willReturn(entities);
            given(reservationMapper.toReservationResponseList(entities)).willReturn(responses);

            // When
            List<ReservationResponse> result = service.listUpcomingReservations(USER_ID);

            // Then
            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // getStats
    // ========================================

    @Nested
    @DisplayName("getStats")
    class GetStats {

        @Test
        @DisplayName("正常系: チームの予約統計が正しく集計される")
        void 統計取得_正常() {
            // Given
            given(reservationRepository.countByTeamIdAndStatus(TEAM_ID, ReservationStatus.PENDING)).willReturn(5L);
            given(reservationRepository.countByTeamIdAndStatus(TEAM_ID, ReservationStatus.CONFIRMED)).willReturn(10L);
            given(reservationRepository.countByTeamIdAndStatus(TEAM_ID, ReservationStatus.CANCELLED)).willReturn(2L);
            given(reservationRepository.countByTeamIdAndStatus(TEAM_ID, ReservationStatus.COMPLETED)).willReturn(20L);
            given(reservationRepository.countByTeamIdAndStatus(TEAM_ID, ReservationStatus.NO_SHOW)).willReturn(1L);

            // When
            ReservationStatsResponse result = service.getStats(TEAM_ID);

            // Then
            assertThat(result.getTotalReservations()).isEqualTo(38L);
            assertThat(result.getPendingCount()).isEqualTo(5L);
            assertThat(result.getConfirmedCount()).isEqualTo(10L);
            assertThat(result.getCancelledCount()).isEqualTo(2L);
            assertThat(result.getCompletedCount()).isEqualTo(20L);
            assertThat(result.getNoShowCount()).isEqualTo(1L);
        }
    }
}
