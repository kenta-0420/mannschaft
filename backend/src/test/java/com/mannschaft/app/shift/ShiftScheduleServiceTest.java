package com.mannschaft.app.shift;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.DomainEventPublisher;
import org.mockito.ArgumentCaptor;
import com.mannschaft.app.shift.dto.CreateShiftScheduleRequest;
import com.mannschaft.app.shift.dto.ShiftScheduleResponse;
import com.mannschaft.app.shift.dto.ShiftScheduleSummaryResponse;
import com.mannschaft.app.shift.dto.UpdateShiftScheduleRequest;
import com.mannschaft.app.shift.entity.ShiftAssignmentEntity;
import com.mannschaft.app.shift.entity.ShiftPositionEntity;
import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftAssignmentRepository;
import com.mannschaft.app.shift.repository.ShiftPositionRepository;
import com.mannschaft.app.shift.repository.ShiftRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.shift.service.ShiftAutoAssignService;
import com.mannschaft.app.shift.service.ShiftScheduleService;
import org.springframework.test.util.ReflectionTestUtils;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
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
    private ShiftSlotRepository slotRepository;

    @Mock
    private ShiftAssignmentRepository assignmentRepository;

    @Mock
    private ShiftRequestRepository requestRepository;

    @Mock
    private ShiftPositionRepository positionRepository;

    @Mock
    private ShiftMapper shiftMapper;

    @Mock
    private ShiftAutoAssignService autoAssignService;

    @Mock
    private DomainEventPublisher eventPublisher;

    @Mock
    private AccessControlService accessControlService;

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
        return ShiftScheduleResponse.builder()
                .id(SCHEDULE_ID)
                .teamId(TEAM_ID)
                .content(new ShiftScheduleResponse.ShiftContentDto("3月第1週シフト", "WEEKLY", null))
                .period(new ShiftScheduleResponse.ShiftPeriodDto(
                        LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 7), null))
                .status(new ShiftScheduleResponse.ShiftStatusDto("DRAFT", null, null))
                .audit(new ShiftScheduleResponse.ShiftAuditDto(USER_ID, LocalDateTime.now(), LocalDateTime.now()))
                .build();
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
            assertThat(result.get(0).getContent().title()).isEqualTo("3月第1週シフト");
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
            assertThat(result.getContent().title()).isEqualTo("3月第1週シフト");
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
            assertThat(result.getContent().title()).isEqualTo("3月第1週シフト");
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

        @Test
        @DisplayName("スケジュール作成_非権限者_COMMON_002")
        void スケジュール作成_非権限者_COMMON_002() {
            // Given
            CreateShiftScheduleRequest req = new CreateShiftScheduleRequest(
                    "3月第1週シフト", "WEEKLY",
                    LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 7),
                    null, null);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");

            // When & Then
            assertThatThrownBy(() -> shiftScheduleService.createSchedule(TEAM_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(CommonErrorCode.COMMON_002);
            verify(scheduleRepository, never()).save(any(ShiftScheduleEntity.class));
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
            ShiftScheduleResponse result = shiftScheduleService.updateSchedule(SCHEDULE_ID, req, USER_ID);

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
            assertThatThrownBy(() -> shiftScheduleService.updateSchedule(SCHEDULE_ID, req, USER_ID))
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
            assertThatThrownBy(() -> shiftScheduleService.updateSchedule(SCHEDULE_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ShiftErrorCode.INVALID_DATE_RANGE));
        }

        @Test
        @DisplayName("スケジュール更新_非権限者_COMMON_002")
        void スケジュール更新_非権限者_COMMON_002() {
            // Given
            ShiftScheduleEntity entity = createScheduleEntity();
            UpdateShiftScheduleRequest req = new UpdateShiftScheduleRequest(
                    "更新タイトル", null, null, null, null, null, null);
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");

            // When & Then
            assertThatThrownBy(() -> shiftScheduleService.updateSchedule(SCHEDULE_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(CommonErrorCode.COMMON_002);
            verify(scheduleRepository, never()).save(any(ShiftScheduleEntity.class));
        }
    }

    // ========================================
    // ToBuilderUpdateRegression (行重複INSERT防止回帰テスト)
    // ========================================

    @Nested
    @DisplayName("ToBuilderUpdateRegression_ShiftSchedule")
    class ToBuilderUpdateRegressionShiftSchedule {

        /**
         * id 採番済みの existing entity を生成する。
         *
         * <p>{@link com.mannschaft.app.common.BaseEntity#id} は setter を持たないため
         * {@link ReflectionTestUtils} で採番済み状態を再現する（DB から findById で取得した
         * managed entity を模す）。
         */
        private ShiftScheduleEntity existingScheduleWithId() {
            ShiftScheduleEntity entity = createScheduleEntity();
            ReflectionTestUtils.setField(entity, "id", SCHEDULE_ID);
            return entity;
        }

        @Test
        @DisplayName("updateSchedule_既存エンティティをUPDATE_id不変かつ同一インスタンスをsave")
        void updateSchedule_既存エンティティをUPDATE_id不変かつ同一インスタンスをsave() {
            // Given: findById で取得した id 採番済みの managed entity
            ShiftScheduleEntity existing = existingScheduleWithId();
            UpdateShiftScheduleRequest req = new UpdateShiftScheduleRequest(
                    "更新後タイトル", null, null, null, null, null, null);
            ShiftScheduleResponse response = createScheduleResponse();

            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(existing));
            given(scheduleRepository.save(any(ShiftScheduleEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(shiftMapper.toScheduleResponse(any(ShiftScheduleEntity.class))).willReturn(response);

            // When
            shiftScheduleService.updateSchedule(SCHEDULE_ID, req, USER_ID);

            // Then: save に渡るのは findById で取得した「まさにその」managed entity
            // （toBuilder().build() で作り直した別インスタンスではない）。
            // id が保持されているので save は UPDATE になり、新規 INSERT（id=null）は起きない。
            ArgumentCaptor<ShiftScheduleEntity> captor = ArgumentCaptor.forClass(ShiftScheduleEntity.class);
            verify(scheduleRepository).save(captor.capture());
            ShiftScheduleEntity saved = captor.getValue();
            assertThat(saved).isSameAs(existing);         // 同一インスタンス（新規作成でない）
            assertThat(saved.getId()).isEqualTo(SCHEDULE_ID); // id 欠落（INSERT 化）が起きていない
            // 部分更新が managed entity に反映されている
            assertThat(saved.getTitle()).isEqualTo("更新後タイトル");
            // 未指定フィールドは現値維持
            assertThat(saved.getPeriodType()).isEqualTo(ShiftPeriodType.WEEKLY);
            assertThat(saved.getStartDate()).isEqualTo(LocalDate.of(2026, 3, 1));
        }

        @Test
        @DisplayName("updateSchedule_periodType更新_enumが正しくセットされる")
        void updateSchedule_periodType更新_enumが正しくセットされる() {
            // Given
            ShiftScheduleEntity existing = existingScheduleWithId();
            UpdateShiftScheduleRequest req = new UpdateShiftScheduleRequest(
                    null, "MONTHLY", null, null, null, null, null);
            ShiftScheduleResponse response = createScheduleResponse();

            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(existing));
            given(scheduleRepository.save(any(ShiftScheduleEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(shiftMapper.toScheduleResponse(any(ShiftScheduleEntity.class))).willReturn(response);

            // When
            shiftScheduleService.updateSchedule(SCHEDULE_ID, req, USER_ID);

            // Then: periodType が enum に解決されて managed entity に反映
            ArgumentCaptor<ShiftScheduleEntity> captor = ArgumentCaptor.forClass(ShiftScheduleEntity.class);
            verify(scheduleRepository).save(captor.capture());
            ShiftScheduleEntity saved = captor.getValue();
            assertThat(saved).isSameAs(existing);
            assertThat(saved.getId()).isEqualTo(SCHEDULE_ID);
            assertThat(saved.getPeriodType()).isEqualTo(ShiftPeriodType.MONTHLY);
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
            shiftScheduleService.deleteSchedule(SCHEDULE_ID, USER_ID);

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
            assertThatThrownBy(() -> shiftScheduleService.deleteSchedule(SCHEDULE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("スケジュール論理削除_非権限者_COMMON_002")
        void スケジュール論理削除_非権限者_COMMON_002() {
            // Given
            ShiftScheduleEntity entity = createScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");

            // When & Then
            assertThatThrownBy(() -> shiftScheduleService.deleteSchedule(SCHEDULE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(CommonErrorCode.COMMON_002);
            verify(scheduleRepository, never()).save(any(ShiftScheduleEntity.class));
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
            shiftScheduleService.transitionStatus(SCHEDULE_ID, "COLLECTING", USER_ID);

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

        @Test
        @DisplayName("ステータス遷移_PUBLISHED_非権限者_COMMON_002")
        void ステータス遷移_PUBLISHED_非権限者_COMMON_002() {
            // Given
            ShiftScheduleEntity entity = createScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");

            // When & Then
            assertThatThrownBy(() -> shiftScheduleService.transitionStatus(SCHEDULE_ID, "PUBLISHED", USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(CommonErrorCode.COMMON_002);
            verify(scheduleRepository, never()).save(any(ShiftScheduleEntity.class));
            verify(autoAssignService, never()).assertNoUnreviewedRuns(SCHEDULE_ID);
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

        @Test
        @DisplayName("スケジュール複製_非権限者(複製元scope由来)_COMMON_002")
        void スケジュール複製_非権限者_COMMON_002() {
            // Given: 複製元(source)のチームに対して権限が無い（BOLA是正の検証）
            ShiftScheduleEntity source = createScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(source));
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");

            // When & Then
            assertThatThrownBy(() -> shiftScheduleService.duplicateSchedule(SCHEDULE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(CommonErrorCode.COMMON_002);
            verify(scheduleRepository, never()).save(any(ShiftScheduleEntity.class));
        }
    }

    // ========================================
    // getScheduleSummary (Phase 11 第二陣 2-α)
    // ========================================

    @Nested
    @DisplayName("getScheduleSummary")
    class GetScheduleSummary {

        @Test
        @DisplayName("スケジュール存在_日付別ポジション別の充足状況を返す")
        void 正常_日付別ポジション別サマリ返却() {
            // Given: スケジュール + 2 日分 × ホール/キッチン 2 ポジションのスロット
            ShiftScheduleEntity schedule = createScheduleEntity();
            ReflectionTestUtils.setField(schedule, "id", SCHEDULE_ID);
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(schedule));

            // ポジション
            ShiftPositionEntity posHall = ShiftPositionEntity.builder().teamId(TEAM_ID).name("ホール").build();
            ReflectionTestUtils.setField(posHall, "id", 1L);
            ShiftPositionEntity posKitchen = ShiftPositionEntity.builder().teamId(TEAM_ID).name("キッチン").build();
            ReflectionTestUtils.setField(posKitchen, "id", 2L);
            given(positionRepository.findByTeamIdOrderByDisplayOrderAsc(TEAM_ID))
                    .willReturn(List.of(posHall, posKitchen));

            // スロット
            ShiftSlotEntity s1 = ShiftSlotEntity.builder()
                    .scheduleId(SCHEDULE_ID).slotDate(LocalDate.of(2026, 3, 1))
                    .startTime(java.time.LocalTime.of(9, 0)).endTime(java.time.LocalTime.of(17, 0))
                    .positionId(1L).requiredCount(3).build();
            ReflectionTestUtils.setField(s1, "id", 1001L);
            ShiftSlotEntity s2 = ShiftSlotEntity.builder()
                    .scheduleId(SCHEDULE_ID).slotDate(LocalDate.of(2026, 3, 1))
                    .startTime(java.time.LocalTime.of(9, 0)).endTime(java.time.LocalTime.of(17, 0))
                    .positionId(2L).requiredCount(2).build();
            ReflectionTestUtils.setField(s2, "id", 1002L);
            given(slotRepository.findByScheduleIdOrderBySlotDateAscStartTimeAsc(SCHEDULE_ID))
                    .willReturn(List.of(s1, s2));

            // 確定アサイン
            ShiftAssignmentEntity a1 = ShiftAssignmentEntity.builder()
                    .slotId(1001L).userId(50L).assignedBy(USER_ID)
                    .status(ShiftAssignmentStatus.CONFIRMED).build();
            ShiftAssignmentEntity a2 = ShiftAssignmentEntity.builder()
                    .slotId(1001L).userId(51L).assignedBy(USER_ID)
                    .status(ShiftAssignmentStatus.PROPOSED).build(); // 確定ではない
            // Phase 11 事後検分 fixup（2026-05-19）: N+1 解消で findAllByScheduleId に一本化したため
            // slot ごとの Mock ではなくスケジュール単位の Mock に変更。Java 側で slotId グルーピングする。
            given(assignmentRepository.findAllByScheduleId(SCHEDULE_ID))
                    .willReturn(List.of(a1, a2));

            // 希望（slot_date 単位の延べ件数 3 件）
            ShiftRequestEntity r1 = ShiftRequestEntity.builder()
                    .scheduleId(SCHEDULE_ID).userId(50L).slotDate(LocalDate.of(2026, 3, 1))
                    .preference(ShiftPreference.PREFERRED).build();
            ShiftRequestEntity r2 = ShiftRequestEntity.builder()
                    .scheduleId(SCHEDULE_ID).userId(51L).slotDate(LocalDate.of(2026, 3, 1))
                    .preference(ShiftPreference.AVAILABLE).build();
            given(requestRepository.findByScheduleIdOrderBySlotDateAsc(SCHEDULE_ID))
                    .willReturn(List.of(r1, r2));

            // When
            ShiftScheduleSummaryResponse response =
                    shiftScheduleService.getScheduleSummary(SCHEDULE_ID, USER_ID);

            // Then
            assertThat(response.getScheduleId()).isEqualTo(SCHEDULE_ID);
            assertThat(response.getSummaryByDate()).hasSize(1);
            ShiftScheduleSummaryResponse.DateSummary day = response.getSummaryByDate().get(0);
            assertThat(day.getDate()).isEqualTo(LocalDate.of(2026, 3, 1));
            assertThat(day.getTotalRequired()).isEqualTo(5);
            assertThat(day.getTotalConfirmed()).isEqualTo(1);
            assertThat(day.getTotalRequested()).isEqualTo(2);
            assertThat(day.getByPosition()).hasSize(2);
            assertThat(day.getByPosition()).extracting("positionName")
                    .containsExactly("ホール", "キッチン");
            assertThat(day.getByPosition().get(0).getConfirmed()).isEqualTo(1);
            assertThat(day.getByPosition().get(0).getRequired()).isEqualTo(3);
            assertThat(day.getByPosition().get(1).getConfirmed()).isZero();
            assertThat(day.getByPosition().get(1).getRequired()).isEqualTo(2);
        }

        @Test
        @DisplayName("スケジュール非存在_BusinessException")
        void 非存在_BusinessException() {
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> shiftScheduleService.getScheduleSummary(SCHEDULE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("スロット 0 件_空リストを返す")
        void スロット0件_空リスト() {
            ShiftScheduleEntity schedule = createScheduleEntity();
            ReflectionTestUtils.setField(schedule, "id", SCHEDULE_ID);
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(schedule));
            given(slotRepository.findByScheduleIdOrderBySlotDateAscStartTimeAsc(SCHEDULE_ID))
                    .willReturn(List.of());
            given(requestRepository.findByScheduleIdOrderBySlotDateAsc(SCHEDULE_ID))
                    .willReturn(List.of());
            given(positionRepository.findByTeamIdOrderByDisplayOrderAsc(TEAM_ID))
                    .willReturn(List.of());

            ShiftScheduleSummaryResponse response =
                    shiftScheduleService.getScheduleSummary(SCHEDULE_ID, USER_ID);

            assertThat(response.getScheduleId()).isEqualTo(SCHEDULE_ID);
            assertThat(response.getSummaryByDate()).isEmpty();
        }

        // ========================================
        // per-scope 認可（Track2 第二陣 / 2026-05-29）
        // ========================================

        @Test
        @DisplayName("非権限者_COMMON_002")
        void 非権限者_COMMON_002() {
            ShiftScheduleEntity schedule = createScheduleEntity();
            ReflectionTestUtils.setField(schedule, "id", SCHEDULE_ID);
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(schedule));
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            // 当該チームの ADMIN/DEPUTY_ADMIN でない → checkAdminOrAbove が COMMON_002 を投げる
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");

            assertThatThrownBy(() -> shiftScheduleService.getScheduleSummary(SCHEDULE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }

        @Test
        @DisplayName("SYSTEM_ADMIN_短絡で通過")
        void SYSTEM_ADMIN_短絡で通過() {
            ShiftScheduleEntity schedule = createScheduleEntity();
            ReflectionTestUtils.setField(schedule, "id", SCHEDULE_ID);
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(schedule));
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);
            given(slotRepository.findByScheduleIdOrderBySlotDateAscStartTimeAsc(SCHEDULE_ID))
                    .willReturn(List.of());
            given(requestRepository.findByScheduleIdOrderBySlotDateAsc(SCHEDULE_ID))
                    .willReturn(List.of());
            given(positionRepository.findByTeamIdOrderByDisplayOrderAsc(TEAM_ID))
                    .willReturn(List.of());

            ShiftScheduleSummaryResponse response =
                    shiftScheduleService.getScheduleSummary(SCHEDULE_ID, USER_ID);

            assertThat(response.getScheduleId()).isEqualTo(SCHEDULE_ID);
            // SYSTEM_ADMIN は team ADMIN チェックを経由しない
            verify(accessControlService, never()).checkAdminOrAbove(anyLong(), anyLong(), anyString());
        }
    }
}
