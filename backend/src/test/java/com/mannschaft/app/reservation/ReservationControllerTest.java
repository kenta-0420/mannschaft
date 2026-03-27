package com.mannschaft.app.reservation;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.reservation.controller.ReservationBusinessHourController;
import com.mannschaft.app.reservation.controller.ReservationCommonController;
import com.mannschaft.app.reservation.controller.TeamReservationController;
import com.mannschaft.app.reservation.controller.TeamReservationLineController;
import com.mannschaft.app.reservation.controller.TeamReservationSlotController;
import com.mannschaft.app.reservation.dto.AdminNoteRequest;
import com.mannschaft.app.reservation.dto.BlockedTimeRequest;
import com.mannschaft.app.reservation.dto.BlockedTimeResponse;
import com.mannschaft.app.reservation.dto.BusinessHourResponse;
import com.mannschaft.app.reservation.dto.BusinessHoursUpdateRequest;
import com.mannschaft.app.reservation.dto.CancelReservationRequest;
import com.mannschaft.app.reservation.dto.CloseSlotRequest;
import com.mannschaft.app.reservation.dto.CreateReminderRequest;
import com.mannschaft.app.reservation.dto.CreateReservationLineRequest;
import com.mannschaft.app.reservation.dto.CreateReservationRequest;
import com.mannschaft.app.reservation.dto.CreateSlotRequest;
import com.mannschaft.app.reservation.dto.ReminderResponse;
import com.mannschaft.app.reservation.dto.RescheduleRequest;
import com.mannschaft.app.reservation.dto.ReservationLineResponse;
import com.mannschaft.app.reservation.dto.ReservationResponse;
import com.mannschaft.app.reservation.dto.ReservationSlotResponse;
import com.mannschaft.app.reservation.dto.ReservationStatsResponse;
import com.mannschaft.app.reservation.dto.UpdateReservationLineRequest;
import com.mannschaft.app.reservation.dto.UpdateSlotRequest;
import com.mannschaft.app.reservation.service.ReservationBusinessHourService;
import com.mannschaft.app.reservation.service.ReservationLineService;
import com.mannschaft.app.reservation.service.ReservationReminderService;
import com.mannschaft.app.reservation.service.ReservationService;
import com.mannschaft.app.reservation.service.ReservationSlotService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

/**
 * 予約モジュール コントローラーの単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("予約コントローラー 単体テスト")
class ReservationControllerTest {

    private static final Long TEAM_ID = 10L;
    private static final Long USER_ID = 1L;
    private static final Long RESERVATION_ID = 100L;
    private static final Long SLOT_ID = 200L;
    private static final Long LINE_ID = 300L;
    private static final Long REMINDER_ID = 400L;

    private ReservationResponse createReservationResponse() {
        return new ReservationResponse(
                RESERVATION_ID, SLOT_ID, LINE_ID, TEAM_ID, USER_ID,
                "CONFIRMED", LocalDateTime.now(), LocalDateTime.now(),
                null, null, null, null, null, null,
                LocalDateTime.now(), LocalDateTime.now()
        );
    }

    private ReservationSlotResponse createSlotResponse() {
        return new ReservationSlotResponse(
                SLOT_ID, TEAM_ID, USER_ID, "相談枠",
                LocalDate.now(), LocalTime.of(10, 0), LocalTime.of(11, 0),
                0, "OPEN", null, null, false,
                BigDecimal.ZERO, null, null, USER_ID,
                LocalDateTime.now(), LocalDateTime.now()
        );
    }

    private ReservationLineResponse createLineResponse() {
        return new ReservationLineResponse(
                LINE_ID, TEAM_ID, "一般予約", null, 1, true,
                null, LocalDateTime.now(), LocalDateTime.now()
        );
    }

    private ReminderResponse createReminderResponse() {
        return new ReminderResponse(
                REMINDER_ID, RESERVATION_ID, LocalDateTime.now().plusHours(1),
                "PENDING", null, LocalDateTime.now()
        );
    }

    private BusinessHourResponse createBusinessHourResponse() {
        return new BusinessHourResponse(1L, TEAM_ID, "MONDAY", true,
                LocalTime.of(9, 0), LocalTime.of(18, 0));
    }

    private BlockedTimeResponse createBlockedTimeResponse() {
        return new BlockedTimeResponse(1L, TEAM_ID, LocalDate.now(),
                LocalTime.of(12, 0), LocalTime.of(13, 0), "昼休憩",
                USER_ID, LocalDateTime.now(), LocalDateTime.now());
    }

    // ========================================
    // TeamReservationController
    // ========================================

    @Nested
    @DisplayName("TeamReservationController")
    class TeamReservationControllerTests {

        @Mock
        private ReservationService reservationService;

        @Mock
        private ReservationReminderService reminderService;

        @InjectMocks
        private TeamReservationController controller;

        @Test
        @DisplayName("チーム予約一覧取得_正常_200返却")
        void チーム予約一覧取得_正常_200返却() {
            // Given
            Page<ReservationResponse> page = new PageImpl<>(
                    List.of(createReservationResponse()), PageRequest.of(0, 20), 1);
            given(reservationService.listTeamReservations(eq(TEAM_ID), eq(null), any()))
                    .willReturn(page);

            // When
            ResponseEntity<PagedResponse<ReservationResponse>> result =
                    controller.listReservations(TEAM_ID, null, 0, 20);

            // Then
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData()).hasSize(1);
        }

        @Test
        @DisplayName("チーム予約一覧取得_ステータスフィルタ_200返却")
        void チーム予約一覧取得_ステータスフィルタ_200返却() {
            Page<ReservationResponse> page = new PageImpl<>(
                    List.of(createReservationResponse()), PageRequest.of(0, 20), 1);
            given(reservationService.listTeamReservations(eq(TEAM_ID), eq("CONFIRMED"), any()))
                    .willReturn(page);

            ResponseEntity<PagedResponse<ReservationResponse>> result =
                    controller.listReservations(TEAM_ID, "CONFIRMED", 0, 20);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("予約詳細取得_正常_200返却")
        void 予約詳細取得_正常_200返却() {
            given(reservationService.getReservation(TEAM_ID, RESERVATION_ID))
                    .willReturn(createReservationResponse());

            ResponseEntity<ApiResponse<ReservationResponse>> result =
                    controller.getReservation(TEAM_ID, RESERVATION_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData().getId()).isEqualTo(RESERVATION_ID);
        }

        @Test
        @DisplayName("予約作成_正常_201返却")
        void 予約作成_正常_201返却() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                CreateReservationRequest request = new CreateReservationRequest(SLOT_ID, LINE_ID, null);
                given(reservationService.createReservation(TEAM_ID, USER_ID, request))
                        .willReturn(createReservationResponse());

                ResponseEntity<ApiResponse<ReservationResponse>> result =
                        controller.createReservation(TEAM_ID, request);

                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            }
        }

        @Test
        @DisplayName("予約確定_正常_200返却")
        void 予約確定_正常_200返却() {
            given(reservationService.confirmReservation(TEAM_ID, RESERVATION_ID))
                    .willReturn(createReservationResponse());

            ResponseEntity<ApiResponse<ReservationResponse>> result =
                    controller.confirmReservation(TEAM_ID, RESERVATION_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("予約キャンセル_管理者_正常_200返却")
        void 予約キャンセル_管理者_正常_200返却() {
            CancelReservationRequest request = new CancelReservationRequest("都合により");
            given(reservationService.cancelByAdmin(TEAM_ID, RESERVATION_ID, request))
                    .willReturn(createReservationResponse());

            ResponseEntity<ApiResponse<ReservationResponse>> result =
                    controller.cancelReservation(TEAM_ID, RESERVATION_ID, request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("予約完了_正常_200返却")
        void 予約完了_正常_200返却() {
            given(reservationService.completeReservation(TEAM_ID, RESERVATION_ID))
                    .willReturn(createReservationResponse());

            ResponseEntity<ApiResponse<ReservationResponse>> result =
                    controller.completeReservation(TEAM_ID, RESERVATION_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("ノーショー_正常_200返却")
        void ノーショー_正常_200返却() {
            given(reservationService.markNoShow(TEAM_ID, RESERVATION_ID))
                    .willReturn(createReservationResponse());

            ResponseEntity<ApiResponse<ReservationResponse>> result =
                    controller.markNoShow(TEAM_ID, RESERVATION_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("予約リスケジュール_正常_200返却")
        void 予約リスケジュール_正常_200返却() {
            RescheduleRequest request = new RescheduleRequest(SLOT_ID + 1);
            given(reservationService.rescheduleReservation(TEAM_ID, RESERVATION_ID, request))
                    .willReturn(createReservationResponse());

            ResponseEntity<ApiResponse<ReservationResponse>> result =
                    controller.rescheduleReservation(TEAM_ID, RESERVATION_ID, request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("管理者メモ更新_正常_200返却")
        void 管理者メモ更新_正常_200返却() {
            AdminNoteRequest request = new AdminNoteRequest("確認済み");
            given(reservationService.updateAdminNote(TEAM_ID, RESERVATION_ID, request))
                    .willReturn(createReservationResponse());

            ResponseEntity<ApiResponse<ReservationResponse>> result =
                    controller.updateAdminNote(TEAM_ID, RESERVATION_ID, request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("予約統計取得_正常_200返却")
        void 予約統計取得_正常_200返却() {
            ReservationStatsResponse stats = new ReservationStatsResponse(10L, 2L, 5L, 1L, 2L, 0L);
            given(reservationService.getStats(TEAM_ID)).willReturn(stats);

            ResponseEntity<ApiResponse<ReservationStatsResponse>> result =
                    controller.getStats(TEAM_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData().getTotalReservations()).isEqualTo(10L);
        }

        @Test
        @DisplayName("リマインダー一覧取得_正常_200返却")
        void リマインダー一覧取得_正常_200返却() {
            given(reminderService.listReminders(RESERVATION_ID))
                    .willReturn(List.of(createReminderResponse()));

            ResponseEntity<ApiResponse<List<ReminderResponse>>> result =
                    controller.listReminders(TEAM_ID, RESERVATION_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData()).hasSize(1);
        }

        @Test
        @DisplayName("リマインダー作成_正常_201返却")
        void リマインダー作成_正常_201返却() {
            CreateReminderRequest request = new CreateReminderRequest(LocalDateTime.now().plusHours(2));
            given(reminderService.createReminder(RESERVATION_ID, request))
                    .willReturn(createReminderResponse());

            ResponseEntity<ApiResponse<ReminderResponse>> result =
                    controller.createReminder(TEAM_ID, RESERVATION_ID, request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
    }

    // ========================================
    // TeamReservationSlotController
    // ========================================

    @Nested
    @DisplayName("TeamReservationSlotController")
    class TeamReservationSlotControllerTests {

        @Mock
        private ReservationSlotService slotService;

        @InjectMocks
        private TeamReservationSlotController controller;

        private LocalDate from = LocalDate.now();
        private LocalDate to = LocalDate.now().plusDays(7);

        @Test
        @DisplayName("スロット一覧取得_正常_200返却")
        void スロット一覧取得_正常_200返却() {
            given(slotService.listSlots(TEAM_ID, from, to))
                    .willReturn(List.of(createSlotResponse()));

            ResponseEntity<ApiResponse<List<ReservationSlotResponse>>> result =
                    controller.listSlots(TEAM_ID, from, to);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData()).hasSize(1);
        }

        @Test
        @DisplayName("利用可能スロット一覧取得_正常_200返却")
        void 利用可能スロット一覧取得_正常_200返却() {
            given(slotService.listAvailableSlots(TEAM_ID, from, to))
                    .willReturn(List.of(createSlotResponse()));

            ResponseEntity<ApiResponse<List<ReservationSlotResponse>>> result =
                    controller.listAvailableSlots(TEAM_ID, from, to);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("スロット詳細取得_正常_200返却")
        void スロット詳細取得_正常_200返却() {
            given(slotService.getSlot(TEAM_ID, SLOT_ID)).willReturn(createSlotResponse());

            ResponseEntity<ApiResponse<ReservationSlotResponse>> result =
                    controller.getSlot(TEAM_ID, SLOT_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData().getId()).isEqualTo(SLOT_ID);
        }

        @Test
        @DisplayName("スロット作成_正常_201返却")
        void スロット作成_正常_201返却() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                CreateSlotRequest request = new CreateSlotRequest(
                        USER_ID, "相談枠", LocalDate.now(),
                        LocalTime.of(10, 0), LocalTime.of(11, 0),
                        null, BigDecimal.ZERO, null
                );
                given(slotService.createSlot(TEAM_ID, request, USER_ID))
                        .willReturn(createSlotResponse());

                ResponseEntity<ApiResponse<ReservationSlotResponse>> result =
                        controller.createSlot(TEAM_ID, request);

                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            }
        }

        @Test
        @DisplayName("スロット更新_正常_200返却")
        void スロット更新_正常_200返却() {
            UpdateSlotRequest request = new UpdateSlotRequest(null, "更新枠", null, null, null, null, null);
            given(slotService.updateSlot(TEAM_ID, SLOT_ID, request))
                    .willReturn(createSlotResponse());

            ResponseEntity<ApiResponse<ReservationSlotResponse>> result =
                    controller.updateSlot(TEAM_ID, SLOT_ID, request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("スロット削除_正常_204返却")
        void スロット削除_正常_204返却() {
            ResponseEntity<Void> result = controller.deleteSlot(TEAM_ID, SLOT_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(slotService).deleteSlot(TEAM_ID, SLOT_ID);
        }

        @Test
        @DisplayName("スロットクローズ_正常_200返却")
        void スロットクローズ_正常_200返却() {
            CloseSlotRequest request = new CloseSlotRequest("MANUAL_CLOSE");
            given(slotService.closeSlot(TEAM_ID, SLOT_ID, request))
                    .willReturn(createSlotResponse());

            ResponseEntity<ApiResponse<ReservationSlotResponse>> result =
                    controller.closeSlot(TEAM_ID, SLOT_ID, request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("スロット再開_正常_200返却")
        void スロット再開_正常_200返却() {
            given(slotService.reopenSlot(TEAM_ID, SLOT_ID))
                    .willReturn(createSlotResponse());

            ResponseEntity<ApiResponse<ReservationSlotResponse>> result =
                    controller.reopenSlot(TEAM_ID, SLOT_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ========================================
    // TeamReservationLineController
    // ========================================

    @Nested
    @DisplayName("TeamReservationLineController")
    class TeamReservationLineControllerTests {

        @Mock
        private ReservationLineService lineService;

        @InjectMocks
        private TeamReservationLineController controller;

        @Test
        @DisplayName("予約ライン一覧取得_正常_200返却")
        void 予約ライン一覧取得_正常_200返却() {
            given(lineService.listLines(TEAM_ID))
                    .willReturn(List.of(createLineResponse()));

            ResponseEntity<ApiResponse<List<ReservationLineResponse>>> result =
                    controller.listLines(TEAM_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData()).hasSize(1);
        }

        @Test
        @DisplayName("予約ライン作成_正常_201返却")
        void 予約ライン作成_正常_201返却() {
            CreateReservationLineRequest request = new CreateReservationLineRequest(
                    "一般予約", null, 1, null);
            given(lineService.createLine(TEAM_ID, request)).willReturn(createLineResponse());

            ResponseEntity<ApiResponse<ReservationLineResponse>> result =
                    controller.createLine(TEAM_ID, request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("予約ライン更新_正常_200返却")
        void 予約ライン更新_正常_200返却() {
            UpdateReservationLineRequest request = new UpdateReservationLineRequest(
                    "更新予約", null, null, true, null);
            given(lineService.updateLine(TEAM_ID, LINE_ID, request))
                    .willReturn(createLineResponse());

            ResponseEntity<ApiResponse<ReservationLineResponse>> result =
                    controller.updateLine(TEAM_ID, LINE_ID, request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("予約ライン削除_正常_204返却")
        void 予約ライン削除_正常_204返却() {
            ResponseEntity<Void> result = controller.deleteLine(TEAM_ID, LINE_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(lineService).deleteLine(TEAM_ID, LINE_ID);
        }
    }

    // ========================================
    // ReservationBusinessHourController
    // ========================================

    @Nested
    @DisplayName("ReservationBusinessHourController")
    class ReservationBusinessHourControllerTests {

        @Mock
        private ReservationBusinessHourService businessHourService;

        @InjectMocks
        private ReservationBusinessHourController controller;

        @Test
        @DisplayName("営業時間取得_正常_200返却")
        void 営業時間取得_正常_200返却() {
            given(businessHourService.getBusinessHours(TEAM_ID))
                    .willReturn(List.of(createBusinessHourResponse()));

            ResponseEntity<ApiResponse<List<BusinessHourResponse>>> result =
                    controller.getBusinessHours(TEAM_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData()).hasSize(1);
        }

        @Test
        @DisplayName("営業時間一括更新_正常_200返却")
        void 営業時間一括更新_正常_200返却() {
            BusinessHoursUpdateRequest request = new BusinessHoursUpdateRequest(List.of());
            given(businessHourService.updateBusinessHours(TEAM_ID, request))
                    .willReturn(List.of(createBusinessHourResponse()));

            ResponseEntity<ApiResponse<List<BusinessHourResponse>>> result =
                    controller.updateBusinessHours(TEAM_ID, request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("ブロック時間一覧取得_正常_200返却")
        void ブロック時間一覧取得_正常_200返却() {
            LocalDate from = LocalDate.now();
            LocalDate to = LocalDate.now().plusDays(30);
            given(businessHourService.listBlockedTimes(TEAM_ID, from, to))
                    .willReturn(List.of(createBlockedTimeResponse()));

            ResponseEntity<ApiResponse<List<BlockedTimeResponse>>> result =
                    controller.listBlockedTimes(TEAM_ID, from, to);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData()).hasSize(1);
        }

        @Test
        @DisplayName("ブロック時間作成_正常_201返却")
        void ブロック時間作成_正常_201返却() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                BlockedTimeRequest request = new BlockedTimeRequest(
                        LocalDate.now(), LocalTime.of(12, 0), LocalTime.of(13, 0), "昼休憩");
                given(businessHourService.createBlockedTime(TEAM_ID, request, USER_ID))
                        .willReturn(createBlockedTimeResponse());

                ResponseEntity<ApiResponse<BlockedTimeResponse>> result =
                        controller.createBlockedTime(TEAM_ID, request);

                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            }
        }

        @Test
        @DisplayName("ブロック時間更新_正常_200返却")
        void ブロック時間更新_正常_200返却() {
            Long blockedId = 1L;
            BlockedTimeRequest request = new BlockedTimeRequest(
                    LocalDate.now(), LocalTime.of(12, 0), LocalTime.of(13, 0), "変更理由");
            given(businessHourService.updateBlockedTime(TEAM_ID, blockedId, request))
                    .willReturn(createBlockedTimeResponse());

            ResponseEntity<ApiResponse<BlockedTimeResponse>> result =
                    controller.updateBlockedTime(TEAM_ID, blockedId, request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("ブロック時間削除_正常_204返却")
        void ブロック時間削除_正常_204返却() {
            Long blockedId = 1L;

            ResponseEntity<Void> result = controller.deleteBlockedTime(TEAM_ID, blockedId);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(businessHourService).deleteBlockedTime(TEAM_ID, blockedId);
        }

        @Test
        @DisplayName("予約設定概要取得_正常_200返却")
        void 予約設定概要取得_正常_200返却() {
            given(businessHourService.hasBusinessHours(TEAM_ID)).willReturn(true);

            ResponseEntity<ApiResponse<Map<String, Object>>> result =
                    controller.getSettings(TEAM_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData()).containsEntry("teamId", TEAM_ID);
            assertThat(result.getBody().getData()).containsEntry("hasBusinessHours", true);
        }
    }

    // ========================================
    // ReservationCommonController
    // ========================================

    @Nested
    @DisplayName("ReservationCommonController")
    class ReservationCommonControllerTests {

        @Mock
        private ReservationService reservationService;

        @InjectMocks
        private ReservationCommonController controller;

        @Test
        @DisplayName("マイ予約一覧取得_正常_200返却")
        void マイ予約一覧取得_正常_200返却() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                given(reservationService.listMyReservations(USER_ID))
                        .willReturn(List.of(createReservationResponse()));

                ResponseEntity<ApiResponse<List<ReservationResponse>>> result =
                        controller.listMyReservations();

                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(result.getBody().getData()).hasSize(1);
            }
        }

        @Test
        @DisplayName("直近の予約一覧取得_正常_200返却")
        void 直近の予約一覧取得_正常_200返却() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                given(reservationService.listUpcomingReservations(USER_ID))
                        .willReturn(List.of(createReservationResponse()));

                ResponseEntity<ApiResponse<List<ReservationResponse>>> result =
                        controller.listUpcomingReservations();

                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(result.getBody().getData()).hasSize(1);
            }
        }

        @Test
        @DisplayName("マイ予約キャンセル_正常_200返却")
        void マイ予約キャンセル_正常_200返却() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                CancelReservationRequest request = new CancelReservationRequest("都合により");
                given(reservationService.cancelByUser(USER_ID, RESERVATION_ID, request))
                        .willReturn(createReservationResponse());

                ResponseEntity<ApiResponse<ReservationResponse>> result =
                        controller.cancelMyReservation(RESERVATION_ID, request);

                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            }
        }
    }
}
