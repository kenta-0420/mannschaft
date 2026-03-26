package com.mannschaft.app.shift;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shift.dto.CreateShiftScheduleRequest;
import com.mannschaft.app.shift.dto.ShiftScheduleResponse;
import com.mannschaft.app.shift.dto.UpdateShiftScheduleRequest;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.service.ShiftScheduleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
 * {@link ShiftScheduleService} の単体テスト。
 * シフトスケジュールのCRUD・ステータス遷移・複製を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftScheduleService 単体テスト")
class ShiftScheduleServiceTest {

    @Mock
    private ShiftScheduleRepository scheduleRepository;

    @Mock
    private ShiftMapper shiftMapper;

    @InjectMocks
    private ShiftScheduleService shiftScheduleService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long TEAM_ID = 1L;
    private static final Long SCHEDULE_ID = 100L;
    private static final Long USER_ID = 10L;

    private ShiftScheduleEntity createScheduleEntity() {
        return ShiftScheduleEntity.builder()
                .teamId(TEAM_ID)
                .title("3月第1週シフト")
                .periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2026, 3, 1))
                .endDate(LocalDate.of(2026, 3, 7))
                .status(ShiftScheduleStatus.DRAFT)
                .createdBy(USER_ID)
                .build();
    }

    private ShiftScheduleResponse createScheduleResponse() {
        return new ShiftScheduleResponse(
                SCHEDULE_ID, TEAM_ID, "3月第1週シフト", "WEEKLY",
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 7),
                "DRAFT", null, null, USER_ID, null, null,
                LocalDateTime.now(), LocalDateTime.now());
    }

    // ========================================
    // listSchedules
    // ========================================

    @Nested
    @DisplayName("listSchedules")
    class ListSchedules {

        @Test
        @DisplayName("チームのスケジュール一覧取得_正常_リスト返却")
        void チームのスケジュール一覧取得_正常_リスト返却() {
            // Given
            ShiftScheduleEntity entity = createScheduleEntity();
            ShiftScheduleResponse response = createScheduleResponse();
            given(scheduleRepository.findByTeamIdOrderByStartDateDesc(TEAM_ID))
                    .willReturn(List.of(entity));
            given(shiftMapper.toScheduleResponseList(List.of(entity)))
                    .willReturn(List.of(response));

            // When
            List<ShiftScheduleResponse> result = shiftScheduleService.listSchedules(TEAM_ID);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTitle()).isEqualTo("3月第1週シフト");
            verify(scheduleRepository).findByTeamIdOrderByStartDateDesc(TEAM_ID);
        }
    }

    // ========================================
    // listSchedulesByPeriod
    // ========================================

    @Nested
    @DisplayName("listSchedulesByPeriod")
    class ListSchedulesByPeriod {

        @Test
        @DisplayName("期間指定一覧取得_正常_フィルタ結果返却")
        void 期間指定一覧取得_正常_フィルタ結果返却() {
            // Given
            LocalDate from = LocalDate.of(2026, 3, 1);
            LocalDate to = LocalDate.of(2026, 3, 31);
            ShiftScheduleEntity entity = createScheduleEntity();
            ShiftScheduleResponse response = createScheduleResponse();
            given(scheduleRepository.findByTeamIdAndStartDateBetweenOrderByStartDateDesc(TEAM_ID, from, to))
                    .willReturn(List.of(entity));
            given(shiftMapper.toScheduleResponseList(List.of(entity)))
                    .willReturn(List.of(response));

            // When
            List<ShiftScheduleResponse> result = shiftScheduleService.listSchedulesByPeriod(TEAM_ID, from, to);

            // Then
            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // getSchedule
    // ========================================

    @Nested
    @DisplayName("getSchedule")
    class GetSchedule {

        @Test
        @DisplayName("スケジュール単体取得_正常_レスポンス返却")
        void スケジュール単体取得_正常_レスポンス返却() {
            // Given
            ShiftScheduleEntity entity = createScheduleEntity();
            ShiftScheduleResponse response = createScheduleResponse();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(shiftMapper.toScheduleResponse(entity)).willReturn(response);

            // When
            ShiftScheduleResponse result = shiftScheduleService.getSchedule(SCHEDULE_ID);

            // Then
            assertThat(result.getTitle()).isEqualTo("3月第1週シフト");
        }

        @Test
        @DisplayName("スケジュール単体取得_存在しない_BusinessException")
        void スケジュール単体取得_存在しない_BusinessException() {
            // Given
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> shiftScheduleService.getSchedule(SCHEDULE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND));
        }
    }

    // ========================================
    // createSchedule
    // ========================================

    @Nested
    @DisplayName("createSchedule")
    class CreateSchedule {

        @Test
        @DisplayName("スケジュール作成_正常_レスポンス返却")
        void スケジュール作成_正常_レスポンス返却() {
            // Given
            CreateShiftScheduleRequest req = new CreateShiftScheduleRequest(
                    "3月第1週シフト", "WEEKLY",
                    LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 7),
                    null, null);
            ShiftScheduleEntity savedEntity = createScheduleEntity();
            ShiftScheduleResponse response = createScheduleResponse();
            given(scheduleRepository.save(any(ShiftScheduleEntity.class))).willReturn(savedEntity);
            given(shiftMapper.toScheduleResponse(savedEntity)).willReturn(response);

            // When
            ShiftScheduleResponse result = shiftScheduleService.createSchedule(TEAM_ID, req, USER_ID);

            // Then
            assertThat(result.getTitle()).isEqualTo("3月第1週シフト");
            verify(scheduleRepository).save(any(ShiftScheduleEntity.class));
        }

        @Test
        @DisplayName("スケジュール作成_開始日が終了日より後_BusinessException")
        void スケジュール作成_開始日が終了日より後_BusinessException() {
            // Given
            CreateShiftScheduleRequest req = new CreateShiftScheduleRequest(
                    "無効スケジュール", null,
                    LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 1),
                    null, null);

            // When & Then
            assertThatThrownBy(() -> shiftScheduleService.createSchedule(TEAM_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ShiftErrorCode.INVALID_DATE_RANGE));
        }

        @Test
        @DisplayName("スケジュール作成_periodType未指定_デフォルトWEEKLY")
        void スケジュール作成_periodType未指定_デフォルトWEEKLY() {
            // Given
            CreateShiftScheduleRequest req = new CreateShiftScheduleRequest(
                    "デフォルト期間タイプ", null,
                    LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 7),
                    null, null);
            ShiftScheduleEntity savedEntity = createScheduleEntity();
            ShiftScheduleResponse response = createScheduleResponse();
            given(scheduleRepository.save(any(ShiftScheduleEntity.class))).willReturn(savedEntity);
            given(shiftMapper.toScheduleResponse(savedEntity)).willReturn(response);

            // When
            ShiftScheduleResponse result = shiftScheduleService.createSchedule(TEAM_ID, req, USER_ID);

            // Then
            assertThat(result).isNotNull();
            verify(scheduleRepository).save(any(ShiftScheduleEntity.class));
        }
    }

    // ========================================
    // updateSchedule
    // ========================================

    @Nested
    @DisplayName("updateSchedule")
    class UpdateSchedule {

        @Test
        @DisplayName("スケジュール更新_正常_更新後レスポンス返却")
        void スケジュール更新_正常_更新後レスポンス返却() {
            // Given
            ShiftScheduleEntity entity = createScheduleEntity();
            UpdateShiftScheduleRequest req = new UpdateShiftScheduleRequest(
                    "更新タイトル", null, null, null, null, null, null);
            ShiftScheduleResponse response = createScheduleResponse();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(scheduleRepository.save(any(ShiftScheduleEntity.class))).willReturn(entity);
            given(shiftMapper.toScheduleResponse(any(ShiftScheduleEntity.class))).willReturn(response);

            // When
            ShiftScheduleResponse result = shiftScheduleService.updateSchedule(SCHEDULE_ID, req);

            // Then
            assertThat(result).isNotNull();
            verify(scheduleRepository).save(any(ShiftScheduleEntity.class));
        }

        @Test
        @DisplayName("スケジュール更新_存在しない_BusinessException")
        void スケジュール更新_存在しない_BusinessException() {
            // Given
            UpdateShiftScheduleRequest req = new UpdateShiftScheduleRequest(
                    "更新タイトル", null, null, null, null, null, null);
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> shiftScheduleService.updateSchedule(SCHEDULE_ID, req))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("スケジュール更新_不正な日付範囲_BusinessException")
        void スケジュール更新_不正な日付範囲_BusinessException() {
            // Given
            ShiftScheduleEntity entity = createScheduleEntity();
            UpdateShiftScheduleRequest req = new UpdateShiftScheduleRequest(
                    null, null, LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 1),
                    null, null, null);
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> shiftScheduleService.updateSchedule(SCHEDULE_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ShiftErrorCode.INVALID_DATE_RANGE));
        }
    }

    // ========================================
    // deleteSchedule
    // ========================================

    @Nested
    @DisplayName("deleteSchedule")
    class DeleteSchedule {

        @Test
        @DisplayName("スケジュール論理削除_正常_softDeleteが呼ばれる")
        void スケジュール論理削除_正常_softDeleteが呼ばれる() {
            // Given
            ShiftScheduleEntity entity = createScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(scheduleRepository.save(entity)).willReturn(entity);

            // When
            shiftScheduleService.deleteSchedule(SCHEDULE_ID);

            // Then
            assertThat(entity.getDeletedAt()).isNotNull();
            verify(scheduleRepository).save(entity);
        }

        @Test
        @DisplayName("スケジュール論理削除_存在しない_BusinessException")
        void スケジュール論理削除_存在しない_BusinessException() {
            // Given
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> shiftScheduleService.deleteSchedule(SCHEDULE_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // transitionStatus
    // ========================================

    @Nested
    @DisplayName("transitionStatus")
    class TransitionStatus {

        @Test
        @DisplayName("ステータス遷移_COLLECTING_正常")
        void ステータス遷移_COLLECTING_正常() {
            // Given
            ShiftScheduleEntity entity = createScheduleEntity();
            ShiftScheduleResponse response = createScheduleResponse();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(scheduleRepository.save(entity)).willReturn(entity);
            given(shiftMapper.toScheduleResponse(entity)).willReturn(response);

            // When
            ShiftScheduleResponse result = shiftScheduleService.transitionStatus(SCHEDULE_ID, "COLLECTING", USER_ID);

            // Then
            assertThat(entity.getStatus()).isEqualTo(ShiftScheduleStatus.COLLECTING);
            verify(scheduleRepository).save(entity);
        }

        @Test
        @DisplayName("ステータス遷移_ADJUSTING_正常")
        void ステータス遷移_ADJUSTING_正常() {
            // Given
            ShiftScheduleEntity entity = createScheduleEntity();
            ShiftScheduleResponse response = createScheduleResponse();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(scheduleRepository.save(entity)).willReturn(entity);
            given(shiftMapper.toScheduleResponse(entity)).willReturn(response);

            // When
            shiftScheduleService.transitionStatus(SCHEDULE_ID, "ADJUSTING", USER_ID);

            // Then
            assertThat(entity.getStatus()).isEqualTo(ShiftScheduleStatus.ADJUSTING);
        }

        @Test
        @DisplayName("ステータス遷移_PUBLISHED_正常_publishedAt設定")
        void ステータス遷移_PUBLISHED_正常_publishedAt設定() {
            // Given
            ShiftScheduleEntity entity = createScheduleEntity();
            ShiftScheduleResponse response = createScheduleResponse();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(scheduleRepository.save(entity)).willReturn(entity);
            given(shiftMapper.toScheduleResponse(entity)).willReturn(response);

            // When
            shiftScheduleService.transitionStatus(SCHEDULE_ID, "PUBLISHED", USER_ID);

            // Then
            assertThat(entity.getStatus()).isEqualTo(ShiftScheduleStatus.PUBLISHED);
            assertThat(entity.getPublishedAt()).isNotNull();
            assertThat(entity.getPublishedBy()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("ステータス遷移_ARCHIVED_正常")
        void ステータス遷移_ARCHIVED_正常() {
            // Given
            ShiftScheduleEntity entity = createScheduleEntity();
            ShiftScheduleResponse response = createScheduleResponse();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(scheduleRepository.save(entity)).willReturn(entity);
            given(shiftMapper.toScheduleResponse(entity)).willReturn(response);

            // When
            shiftScheduleService.transitionStatus(SCHEDULE_ID, "ARCHIVED", USER_ID);

            // Then
            assertThat(entity.getStatus()).isEqualTo(ShiftScheduleStatus.ARCHIVED);
        }

        @Test
        @DisplayName("ステータス遷移_DRAFT_BusinessException")
        void ステータス遷移_DRAFT_BusinessException() {
            // Given
            ShiftScheduleEntity entity = createScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> shiftScheduleService.transitionStatus(SCHEDULE_ID, "DRAFT", USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ShiftErrorCode.INVALID_SCHEDULE_STATUS));
        }
    }

    // ========================================
    // duplicateSchedule
    // ========================================

    @Nested
    @DisplayName("duplicateSchedule")
    class DuplicateSchedule {

        @Test
        @DisplayName("スケジュール複製_正常_DRAFTで新規作成")
        void スケジュール複製_正常_DRAFTで新規作成() {
            // Given
            ShiftScheduleEntity source = createScheduleEntity();
            ShiftScheduleEntity duplicate = createScheduleEntity();
            ShiftScheduleResponse response = createScheduleResponse();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(source));
            given(scheduleRepository.save(any(ShiftScheduleEntity.class))).willReturn(duplicate);
            given(shiftMapper.toScheduleResponse(duplicate)).willReturn(response);

            // When
            ShiftScheduleResponse result = shiftScheduleService.duplicateSchedule(SCHEDULE_ID, USER_ID);

            // Then
            assertThat(result).isNotNull();
            verify(scheduleRepository).save(any(ShiftScheduleEntity.class));
        }

        @Test
        @DisplayName("スケジュール複製_存在しない_BusinessException")
        void スケジュール複製_存在しない_BusinessException() {
            // Given
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> shiftScheduleService.duplicateSchedule(SCHEDULE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
