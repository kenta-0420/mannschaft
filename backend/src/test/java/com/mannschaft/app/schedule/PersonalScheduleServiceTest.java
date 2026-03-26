package com.mannschaft.app.schedule;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.schedule.dto.BatchDeleteResponse;
import com.mannschaft.app.schedule.dto.CreatePersonalScheduleRequest;
import com.mannschaft.app.schedule.dto.PersonalScheduleResponse;
import com.mannschaft.app.schedule.dto.UpdatePersonalScheduleRequest;
import com.mannschaft.app.schedule.entity.PersonalScheduleReminderEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.PersonalScheduleReminderRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.service.PersonalScheduleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link PersonalScheduleService} の単体テスト。
 * 個人スケジュールのCRUD・繰り返し・リマインダー・一括削除を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PersonalScheduleService 単体テスト")
class PersonalScheduleServiceTest {

    @Mock
    private ScheduleRepository scheduleRepository;

    @Mock
    private PersonalScheduleReminderRepository reminderRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private PersonalScheduleService personalScheduleService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long SCHEDULE_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long OTHER_USER_ID = 999L;
    private static final LocalDateTime START = LocalDateTime.of(2026, 4, 1, 10, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 4, 1, 12, 0);

    private ScheduleEntity createPersonalScheduleEntity() {
        return ScheduleEntity.builder()
                .userId(USER_ID)
                .title("個人予定")
                .description("テスト")
                .location("自宅")
                .startAt(START)
                .endAt(END)
                .allDay(false)
                .eventType(EventType.OTHER)
                .color("#FF0000")
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.ADMIN_ONLY)
                .minResponseRole(MinResponseRole.ADMIN_ONLY)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(false)
                .attendanceStatus(AttendanceGenerationStatus.READY)
                .commentOption(CommentOption.HIDDEN)
                .isException(false)
                .createdBy(USER_ID)
                .build();
    }

    private ScheduleEntity createCancelledPersonalScheduleEntity() {
        return ScheduleEntity.builder()
                .userId(USER_ID)
                .title("キャンセル済み")
                .startAt(START)
                .endAt(END)
                .allDay(false)
                .eventType(EventType.OTHER)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.ADMIN_ONLY)
                .status(ScheduleStatus.CANCELLED)
                .isException(false)
                .createdBy(USER_ID)
                .build();
    }

    // ========================================
    // createPersonalSchedule
    // ========================================

    @Nested
    @DisplayName("createPersonalSchedule")
    class CreatePersonalSchedule {

        @Test
        @DisplayName("個人スケジュール作成_正常_保存されてイベント発行される")
        void 個人スケジュール作成_正常_保存されてイベント発行される() {
            // given
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(
                    eq(USER_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of());
            given(scheduleRepository.save(any(ScheduleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            CreatePersonalScheduleRequest req = new CreatePersonalScheduleRequest(
                    "個人予定", "テスト", "自宅", START, END, false, "OTHER", "#FF0000",
                    null, null);

            // when
            PersonalScheduleResponse result = personalScheduleService.createPersonalSchedule(req, USER_ID);

            // then
            assertThat(result.getTitle()).isEqualTo("個人予定");
            assertThat(result.getStatus()).isEqualTo("SCHEDULED");
            verify(scheduleRepository).save(any(ScheduleEntity.class));
            verify(eventPublisher).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("個人スケジュール作成_日付不正_例外スロー")
        void 個人スケジュール作成_日付不正_例外スロー() {
            // given
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(
                    eq(USER_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of());

            CreatePersonalScheduleRequest req = new CreatePersonalScheduleRequest(
                    "個人予定", null, null, END, START, false, null, null, null, null);

            // when & then
            assertThatThrownBy(() -> personalScheduleService.createPersonalSchedule(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.INVALID_DATE_RANGE);
        }

        @Test
        @DisplayName("個人スケジュール作成_上限超過_例外スロー")
        void 個人スケジュール作成_上限超過_例外スロー() {
            // given
            List<ScheduleEntity> thousandSchedules =
                    java.util.stream.IntStream.range(0, 1000)
                            .mapToObj(i -> createPersonalScheduleEntity())
                            .toList();
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(
                    eq(USER_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(thousandSchedules);

            CreatePersonalScheduleRequest req = new CreatePersonalScheduleRequest(
                    "個人予定", null, null, START, END, false, null, null, null, null);

            // when & then
            assertThatThrownBy(() -> personalScheduleService.createPersonalSchedule(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.PERSONAL_SCHEDULE_LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("個人スケジュール作成_リマインダー付き_リマインダーも保存される")
        void 個人スケジュール作成_リマインダー付き_リマインダーも保存される() {
            // given
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(
                    eq(USER_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of());
            given(scheduleRepository.save(any(ScheduleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            CreatePersonalScheduleRequest req = new CreatePersonalScheduleRequest(
                    "個人予定", null, null, START, END, false, null, null,
                    List.of(10, 30), null);

            // when
            PersonalScheduleResponse result = personalScheduleService.createPersonalSchedule(req, USER_ID);

            // then
            verify(reminderRepository).deleteByScheduleId(any());
            verify(reminderRepository).saveAll(any());
        }
    }

    // ========================================
    // listPersonalSchedules
    // ========================================

    @Nested
    @DisplayName("listPersonalSchedules")
    class ListPersonalSchedules {

        @Test
        @DisplayName("一覧取得_正常_スケジュール一覧を返す")
        void 一覧取得_正常_スケジュール一覧を返す() {
            // given
            ScheduleEntity entity = createPersonalScheduleEntity();
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(USER_ID, START, END))
                    .willReturn(List.of(entity));

            // when
            List<PersonalScheduleResponse> result =
                    personalScheduleService.listPersonalSchedules(USER_ID, START, END, null, null, null, 20);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTitle()).isEqualTo("個人予定");
        }

        @Test
        @DisplayName("一覧取得_キーワード検索_一致するもののみ返す")
        void 一覧取得_キーワード検索_一致するもののみ返す() {
            // given
            ScheduleEntity entity = createPersonalScheduleEntity();
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(USER_ID, START, END))
                    .willReturn(List.of(entity));

            // when
            List<PersonalScheduleResponse> result =
                    personalScheduleService.listPersonalSchedules(USER_ID, START, END, "個人", null, null, 20);

            // then
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("一覧取得_キーワード不一致_空リスト")
        void 一覧取得_キーワード不一致_空リスト() {
            // given
            ScheduleEntity entity = createPersonalScheduleEntity();
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(USER_ID, START, END))
                    .willReturn(List.of(entity));

            // when
            List<PersonalScheduleResponse> result =
                    personalScheduleService.listPersonalSchedules(USER_ID, START, END, "存在しない", null, null, 20);

            // then
            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // getPersonalSchedule
    // ========================================

    @Nested
    @DisplayName("getPersonalSchedule")
    class GetPersonalSchedule {

        @Test
        @DisplayName("詳細取得_正常_レスポンスを返す")
        void 詳細取得_正常_レスポンスを返す() {
            // given
            ScheduleEntity entity = createPersonalScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));

            // when
            PersonalScheduleResponse result = personalScheduleService.getPersonalSchedule(SCHEDULE_ID, USER_ID);

            // then
            assertThat(result.getTitle()).isEqualTo("個人予定");
        }

        @Test
        @DisplayName("詳細取得_他人のスケジュール_例外スロー")
        void 詳細取得_他人のスケジュール_例外スロー() {
            // given
            ScheduleEntity entity = createPersonalScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));

            // when & then
            assertThatThrownBy(() -> personalScheduleService.getPersonalSchedule(SCHEDULE_ID, OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.NOT_SCHEDULE_OWNER);
        }

        @Test
        @DisplayName("詳細取得_不存在_例外スロー")
        void 詳細取得_不存在_例外スロー() {
            // given
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> personalScheduleService.getPersonalSchedule(SCHEDULE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.SCHEDULE_NOT_FOUND);
        }
    }

    // ========================================
    // updatePersonalSchedule
    // ========================================

    @Nested
    @DisplayName("updatePersonalSchedule")
    class UpdatePersonalSchedule {

        @Test
        @DisplayName("更新_正常_更新されてイベント発行される")
        void 更新_正常_更新されてイベント発行される() {
            // given
            ScheduleEntity entity = createPersonalScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(scheduleRepository.save(any(ScheduleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(reminderRepository.findByScheduleIdOrderByRemindBeforeMinutesAsc(any()))
                    .willReturn(List.of());

            UpdatePersonalScheduleRequest req = new UpdatePersonalScheduleRequest(
                    "更新タイトル", null, null, null, null, null, null, null, null, null, null);

            // when
            PersonalScheduleResponse result =
                    personalScheduleService.updatePersonalSchedule(SCHEDULE_ID, req, USER_ID);

            // then
            verify(eventPublisher).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("更新_他人のスケジュール_例外スロー")
        void 更新_他人のスケジュール_例外スロー() {
            // given
            ScheduleEntity entity = createPersonalScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));

            UpdatePersonalScheduleRequest req = new UpdatePersonalScheduleRequest(
                    "更新", null, null, null, null, null, null, null, null, null, null);

            // when & then
            assertThatThrownBy(() -> personalScheduleService.updatePersonalSchedule(SCHEDULE_ID, req, OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.NOT_SCHEDULE_OWNER);
        }

        @Test
        @DisplayName("更新_キャンセル済み_例外スロー")
        void 更新_キャンセル済み_例外スロー() {
            // given
            ScheduleEntity cancelled = createCancelledPersonalScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(cancelled));

            UpdatePersonalScheduleRequest req = new UpdatePersonalScheduleRequest(
                    "更新", null, null, null, null, null, null, null, null, null, null);

            // when & then
            assertThatThrownBy(() -> personalScheduleService.updatePersonalSchedule(SCHEDULE_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.SCHEDULE_ALREADY_CANCELLED);
        }
    }

    // ========================================
    // deletePersonalSchedule
    // ========================================

    @Nested
    @DisplayName("deletePersonalSchedule")
    class DeletePersonalSchedule {

        @Test
        @DisplayName("削除_単体_論理削除される")
        void 削除_単体_論理削除される() {
            // given
            ScheduleEntity entity = createPersonalScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(scheduleRepository.save(any(ScheduleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // when
            personalScheduleService.deletePersonalSchedule(SCHEDULE_ID, "THIS_ONLY", USER_ID);

            // then
            assertThat(entity.getDeletedAt()).isNotNull();
            verify(eventPublisher).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("削除_他人のスケジュール_例外スロー")
        void 削除_他人のスケジュール_例外スロー() {
            // given
            ScheduleEntity entity = createPersonalScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));

            // when & then
            assertThatThrownBy(() -> personalScheduleService.deletePersonalSchedule(SCHEDULE_ID, null, OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.NOT_SCHEDULE_OWNER);
        }
    }

    // ========================================
    // batchDeletePersonalSchedules
    // ========================================

    @Nested
    @DisplayName("batchDeletePersonalSchedules")
    class BatchDeletePersonalSchedules {

        @Test
        @DisplayName("一括削除_正常_削除件数とスキップ件数を返す")
        void 一括削除_正常_削除件数とスキップ件数を返す() {
            // given
            ScheduleEntity ownSchedule = createPersonalScheduleEntity();
            given(scheduleRepository.findById(1L)).willReturn(Optional.of(ownSchedule));
            given(scheduleRepository.findById(2L)).willReturn(Optional.empty());
            given(scheduleRepository.save(any(ScheduleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // when
            BatchDeleteResponse result =
                    personalScheduleService.batchDeletePersonalSchedules(List.of(1L, 2L), USER_ID);

            // then
            assertThat(result.getDeletedCount()).isEqualTo(1);
            assertThat(result.getSkippedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("一括削除_上限超過_例外スロー")
        void 一括削除_上限超過_例外スロー() {
            // given
            List<Long> ids = java.util.stream.LongStream.range(1, 52).boxed().toList();

            // when & then
            assertThatThrownBy(() -> personalScheduleService.batchDeletePersonalSchedules(ids, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.BATCH_DELETE_LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("一括削除_他人のスケジュール_スキップされる")
        void 一括削除_他人のスケジュール_スキップされる() {
            // given
            ScheduleEntity otherUserSchedule = ScheduleEntity.builder()
                    .userId(OTHER_USER_ID)
                    .title("他人の予定")
                    .startAt(START)
                    .endAt(END)
                    .allDay(false)
                    .eventType(EventType.OTHER)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.ADMIN_ONLY)
                    .status(ScheduleStatus.SCHEDULED)
                    .isException(false)
                    .build();
            given(scheduleRepository.findById(1L)).willReturn(Optional.of(otherUserSchedule));

            // when
            BatchDeleteResponse result =
                    personalScheduleService.batchDeletePersonalSchedules(List.of(1L), USER_ID);

            // then
            assertThat(result.getDeletedCount()).isZero();
            assertThat(result.getSkippedCount()).isEqualTo(1);
        }
    }
}
