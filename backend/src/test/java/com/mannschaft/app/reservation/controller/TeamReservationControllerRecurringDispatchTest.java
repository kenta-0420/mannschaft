package com.mannschaft.app.reservation.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.reservation.dto.CreateReservationRequest;
import com.mannschaft.app.reservation.dto.ReservationResponse;
import com.mannschaft.app.reservation.service.ReservationRecurringService;
import com.mannschaft.app.reservation.service.ReservationReminderService;
import com.mannschaft.app.reservation.service.ReservationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 予約作成 EP の定期予約ディスパッチ検証（F03.4.5 §6.2 W2-5・AC-5-2）。
 *
 * <p><b>なぜ Controller 層で分岐するのか（このテストが守る設計）</b>: 定期予約は「週ごと独立
 * トランザクション」が要件（AC-5-5）であり、オーケストレーターは非トランザクションでなければならない。
 * 一方 {@code ReservationService.createReservation} は {@code @Transactional} である。
 * Service 層で分岐すると外側にトランザクションが張られ、1 週の失敗が全週を巻き込む
 * （participating tx の rollback-only マーク）。よってトランザクションを開く前の層で分岐する。</p>
 *
 * <p>AC-5-2 後半「省略時 / {@code repeatWeeks=1} は従来と完全同一」を、
 * <b>従来経路の Service が呼ばれ、定期予約経路が一切呼ばれない</b>ことで固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("予約作成EP 定期予約ディスパッチテスト（F03.4.5 §6.2 / AC-5-2）")
class TeamReservationControllerRecurringDispatchTest {

    private static final Long TEAM_ID = 3101L;
    private static final Long USER_ID = 3102L;
    private static final Long SLOT_ID = 3103L;
    private static final Long LINE_ID = 3104L;

    @Mock
    private ReservationService reservationService;
    @Mock
    private ReservationReminderService reminderService;
    @Mock
    private ReservationRecurringService recurringService;

    @InjectMocks
    private TeamReservationController controller;

    private CreateReservationRequest request(Integer repeatWeeks) {
        return new CreateReservationRequest(SLOT_ID, LINE_ID, null, repeatWeeks);
    }

    @Test
    @DisplayName("AC-5-2: repeatWeeks 省略は従来の単発経路（定期経路を呼ばない）")
    void 省略時は従来経路() {
        given(reservationService.createReservation(anyLong(), anyLong(), any()))
                .willReturn(ReservationResponse.builder().id(1L).build());

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
            CreateReservationRequest req = request(null);

            ResponseEntity<ApiResponse<ReservationResponse>> result =
                    controller.createReservation(TEAM_ID, req);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            verify(reservationService).createReservation(TEAM_ID, USER_ID, req);
            verify(recurringService, never()).createRecurring(anyLong(), anyLong(), any());
        }
    }

    @Test
    @DisplayName("AC-5-2(境界): repeatWeeks=1 も従来の単発経路（series を発行しない）")
    void 一週指定は従来経路() {
        given(reservationService.createReservation(anyLong(), anyLong(), any()))
                .willReturn(ReservationResponse.builder().id(1L).build());

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
            CreateReservationRequest req = request(1);

            controller.createReservation(TEAM_ID, req);

            verify(reservationService).createReservation(TEAM_ID, USER_ID, req);
            verify(recurringService, never()).createRecurring(anyLong(), anyLong(), any());
        }
    }

    @Test
    @DisplayName("AC-5-2(境界): repeatWeeks=2 以上は定期予約経路（従来経路を呼ばない）")
    void 二週以上は定期経路() {
        given(recurringService.createRecurring(anyLong(), anyLong(), any()))
                .willReturn(ReservationResponse.builder().id(1L).build());

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
            CreateReservationRequest req = request(2);

            ResponseEntity<ApiResponse<ReservationResponse>> result =
                    controller.createReservation(TEAM_ID, req);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            verify(recurringService).createRecurring(TEAM_ID, USER_ID, req);
            verify(reservationService, never()).createReservation(anyLong(), anyLong(), any());
        }
    }
}
