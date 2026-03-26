package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.ShiftMapper;
import com.mannschaft.app.shift.ShiftPreference;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.dto.CreateShiftRequestRequest;
import com.mannschaft.app.shift.dto.ShiftRequestResponse;
import com.mannschaft.app.shift.dto.ShiftRequestSummaryResponse;
import com.mannschaft.app.shift.dto.UpdateShiftRequestRequest;
import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.repository.ShiftRequestRepository;
import com.mannschaft.app.shift.service.ShiftRequestService;
import com.mannschaft.app.shift.service.ShiftScheduleService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ShiftRequestService} の単体テスト。
 * シフト希望の提出・更新・削除・サマリーを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftRequestService 単体テスト")
class ShiftRequestServiceTest {

    @Mock
    private ShiftRequestRepository requestRepository;

    @Mock
    private ShiftScheduleService scheduleService;

    @Mock
    private ShiftMapper shiftMapper;

    @Mock
    private UserRoleRepository userRoleRepository;

    @InjectMocks
    private ShiftRequestService shiftRequestService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long SCHEDULE_ID = 100L;
    private static final Long USER_ID = 10L;
    private static final Long REQUEST_ID = 300L;
    private static final Long TEAM_ID = 1L;

    private ShiftScheduleEntity createCollectingSchedule() {
        ShiftScheduleEntity entity = ShiftScheduleEntity.builder()
                .teamId(TEAM_ID)
                .title("テストスケジュール")
                .startDate(LocalDate.of(2026, 3, 1))
                .endDate(LocalDate.of(2026, 3, 7))
                .status(ShiftScheduleStatus.COLLECTING)
                .requestDeadline(LocalDateTime.now().plusDays(7))
                .build();
        return entity;
    }

    private ShiftScheduleEntity createDraftSchedule() {
        return ShiftScheduleEntity.builder()
                .teamId(TEAM_ID)
                .title("下書きスケジュール")
                .startDate(LocalDate.of(2026, 3, 1))
                .endDate(LocalDate.of(2026, 3, 7))
                .status(ShiftScheduleStatus.DRAFT)
                .build();
    }

    private ShiftScheduleEntity createExpiredSchedule() {
        return ShiftScheduleEntity.builder()
                .teamId(TEAM_ID)
                .title("期限切れスケジュール")
                .startDate(LocalDate.of(2026, 3, 1))
                .endDate(LocalDate.of(2026, 3, 7))
                .status(ShiftScheduleStatus.COLLECTING)
                .requestDeadline(LocalDateTime.now().minusDays(1))
                .build();
    }

    private ShiftRequestEntity createRequestEntity() {
        ShiftRequestEntity entity = ShiftRequestEntity.builder()
                .scheduleId(SCHEDULE_ID)
                .userId(USER_ID)
                .slotDate(LocalDate.of(2026, 3, 2))
                .preference(ShiftPreference.PREFERRED)
                .note("希望します")
                .build();
        callOnCreate(entity);
        return entity;
    }

    private ShiftRequestResponse createRequestResponse() {
        return new ShiftRequestResponse(
                REQUEST_ID, SCHEDULE_ID, USER_ID, null,
                LocalDate.of(2026, 3, 2), "PREFERRED", "希望します",
                LocalDateTime.now());
    }

    private void callOnCreate(ShiftRequestEntity entity) {
        try {
            Method method = ShiftRequestEntity.class.getDeclaredMethod("onCreate");
            method.setAccessible(true);
            method.invoke(entity);
        } catch (Exception ignored) {
        }
    }

    // ========================================
    // listRequests
    // ========================================

    @Nested
    @DisplayName("listRequests")
    class ListRequests {

        @Test
        @DisplayName("スケジュールの希望一覧取得_正常_リスト返却")
        void スケジュールの希望一覧取得_正常_リスト返却() {
            // Given
            ShiftRequestEntity entity = createRequestEntity();
            ShiftRequestResponse response = createRequestResponse();
            given(requestRepository.findByScheduleIdOrderBySlotDateAsc(SCHEDULE_ID))
                    .willReturn(List.of(entity));
            given(shiftMapper.toRequestResponseList(List.of(entity)))
                    .willReturn(List.of(response));

            // When
            List<ShiftRequestResponse> result = shiftRequestService.listRequests(SCHEDULE_ID);

            // Then
            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // listMyRequests
    // ========================================

    @Nested
    @DisplayName("listMyRequests")
    class ListMyRequests {

        @Test
        @DisplayName("自分の希望一覧取得_正常_リスト返却")
        void 自分の希望一覧取得_正常_リスト返却() {
            // Given
            ShiftRequestEntity entity = createRequestEntity();
            ShiftRequestResponse response = createRequestResponse();
            given(requestRepository.findByUserIdOrderBySlotDateDesc(USER_ID))
                    .willReturn(List.of(entity));
            given(shiftMapper.toRequestResponseList(List.of(entity)))
                    .willReturn(List.of(response));

            // When
            List<ShiftRequestResponse> result = shiftRequestService.listMyRequests(USER_ID);

            // Then
            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // submitRequest
    // ========================================

    @Nested
    @DisplayName("submitRequest")
    class SubmitRequest {

        @Test
        @DisplayName("シフト希望提出_正常_レスポンス返却")
        void シフト希望提出_正常_レスポンス返却() {
            // Given
            CreateShiftRequestRequest req = new CreateShiftRequestRequest(
                    SCHEDULE_ID, null, LocalDate.of(2026, 3, 2), "PREFERRED", "希望します");
            ShiftScheduleEntity schedule = createCollectingSchedule();
            ShiftRequestEntity savedEntity = createRequestEntity();
            ShiftRequestResponse response = createRequestResponse();

            given(scheduleService.findScheduleOrThrow(SCHEDULE_ID)).willReturn(schedule);
            given(requestRepository.findByScheduleIdAndUserIdAndSlotDate(
                    SCHEDULE_ID, USER_ID, LocalDate.of(2026, 3, 2)))
                    .willReturn(Optional.empty());
            given(requestRepository.save(any(ShiftRequestEntity.class))).willReturn(savedEntity);
            given(shiftMapper.toRequestResponse(savedEntity)).willReturn(response);

            // When
            ShiftRequestResponse result = shiftRequestService.submitRequest(req, USER_ID);

            // Then
            assertThat(result).isNotNull();
            verify(requestRepository).save(any(ShiftRequestEntity.class));
        }

        @Test
        @DisplayName("シフト希望提出_COLLECTING以外_BusinessException")
        void シフト希望提出_COLLECTING以外_BusinessException() {
            // Given
            CreateShiftRequestRequest req = new CreateShiftRequestRequest(
                    SCHEDULE_ID, null, LocalDate.of(2026, 3, 2), "PREFERRED", null);
            ShiftScheduleEntity schedule = createDraftSchedule();
            given(scheduleService.findScheduleOrThrow(SCHEDULE_ID)).willReturn(schedule);

            // When & Then
            assertThatThrownBy(() -> shiftRequestService.submitRequest(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ShiftErrorCode.INVALID_SCHEDULE_STATUS));
        }

        @Test
        @DisplayName("シフト希望提出_期限超過_BusinessException")
        void シフト希望提出_期限超過_BusinessException() {
            // Given
            CreateShiftRequestRequest req = new CreateShiftRequestRequest(
                    SCHEDULE_ID, null, LocalDate.of(2026, 3, 2), "PREFERRED", null);
            ShiftScheduleEntity schedule = createExpiredSchedule();
            given(scheduleService.findScheduleOrThrow(SCHEDULE_ID)).willReturn(schedule);

            // When & Then
            assertThatThrownBy(() -> shiftRequestService.submitRequest(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ShiftErrorCode.REQUEST_DEADLINE_PASSED));
        }

        @Test
        @DisplayName("シフト希望提出_重複_BusinessException")
        void シフト希望提出_重複_BusinessException() {
            // Given
            CreateShiftRequestRequest req = new CreateShiftRequestRequest(
                    SCHEDULE_ID, null, LocalDate.of(2026, 3, 2), "PREFERRED", null);
            ShiftScheduleEntity schedule = createCollectingSchedule();
            ShiftRequestEntity existing = createRequestEntity();

            given(scheduleService.findScheduleOrThrow(SCHEDULE_ID)).willReturn(schedule);
            given(requestRepository.findByScheduleIdAndUserIdAndSlotDate(
                    SCHEDULE_ID, USER_ID, LocalDate.of(2026, 3, 2)))
                    .willReturn(Optional.of(existing));

            // When & Then
            assertThatThrownBy(() -> shiftRequestService.submitRequest(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ShiftErrorCode.REQUEST_ALREADY_EXISTS));
        }
    }

    // ========================================
    // updateRequest
    // ========================================

    @Nested
    @DisplayName("updateRequest")
    class UpdateRequest {

        @Test
        @DisplayName("シフト希望更新_正常_更新後レスポンス返却")
        void シフト希望更新_正常_更新後レスポンス返却() {
            // Given
            ShiftRequestEntity entity = createRequestEntity();
            ShiftScheduleEntity schedule = createCollectingSchedule();
            UpdateShiftRequestRequest req = new UpdateShiftRequestRequest("AVAILABLE", "変更しました");
            ShiftRequestResponse response = createRequestResponse();

            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(entity));
            given(scheduleService.findScheduleOrThrow(entity.getScheduleId())).willReturn(schedule);
            given(requestRepository.save(entity)).willReturn(entity);
            given(shiftMapper.toRequestResponse(entity)).willReturn(response);

            // When
            ShiftRequestResponse result = shiftRequestService.updateRequest(REQUEST_ID, req, USER_ID);

            // Then
            assertThat(entity.getPreference()).isEqualTo(ShiftPreference.AVAILABLE);
            assertThat(entity.getNote()).isEqualTo("変更しました");
        }

        @Test
        @DisplayName("シフト希望更新_存在しない_BusinessException")
        void シフト希望更新_存在しない_BusinessException() {
            // Given
            UpdateShiftRequestRequest req = new UpdateShiftRequestRequest("AVAILABLE", null);
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> shiftRequestService.updateRequest(REQUEST_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // deleteRequest
    // ========================================

    @Nested
    @DisplayName("deleteRequest")
    class DeleteRequest {

        @Test
        @DisplayName("シフト希望削除_正常_deleteが呼ばれる")
        void シフト希望削除_正常_deleteが呼ばれる() {
            // Given
            ShiftRequestEntity entity = createRequestEntity();
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(entity));

            // When
            shiftRequestService.deleteRequest(REQUEST_ID);

            // Then
            verify(requestRepository).delete(entity);
        }

        @Test
        @DisplayName("シフト希望削除_存在しない_BusinessException")
        void シフト希望削除_存在しない_BusinessException() {
            // Given
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> shiftRequestService.deleteRequest(REQUEST_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // getRequestSummary
    // ========================================

    @Nested
    @DisplayName("getRequestSummary")
    class GetRequestSummary {

        @Test
        @DisplayName("希望提出サマリー取得_正常_カウント正確")
        void 希望提出サマリー取得_正常_カウント正確() {
            // Given
            ShiftScheduleEntity schedule = createCollectingSchedule();
            given(requestRepository.countDistinctUserIdByScheduleId(SCHEDULE_ID)).willReturn(3L);
            given(scheduleService.findScheduleOrThrow(SCHEDULE_ID)).willReturn(schedule);
            given(userRoleRepository.countByTeamId(TEAM_ID)).willReturn(10L);

            // When
            ShiftRequestSummaryResponse result = shiftRequestService.getRequestSummary(SCHEDULE_ID);

            // Then
            assertThat(result.getScheduleId()).isEqualTo(SCHEDULE_ID);
            assertThat(result.getTotalMembers()).isEqualTo(10L);
            assertThat(result.getSubmittedCount()).isEqualTo(3L);
            assertThat(result.getPendingCount()).isEqualTo(7L);
        }
    }
}
