package com.mannschaft.app.schedule;

import com.mannschaft.app.schedule.dto.AttendanceResponse;
import com.mannschaft.app.schedule.dto.CrossRefResponse;
import com.mannschaft.app.schedule.dto.EventSurveyResponse;
import com.mannschaft.app.schedule.dto.ReminderResponse;
import com.mannschaft.app.schedule.dto.ScheduleDetailResponse;
import com.mannschaft.app.schedule.dto.ScheduleResponse;
import com.mannschaft.app.schedule.dto.SurveyResponseDetailResponse;
import com.mannschaft.app.schedule.entity.EventSurveyEntity;
import com.mannschaft.app.schedule.entity.EventSurveyResponseEntity;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceEntity;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceReminderEntity;
import com.mannschaft.app.schedule.entity.ScheduleCrossRefEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ScheduleMapper} (MapStruct生成実装) の単体テスト。
 * ScheduleMapperImpl を直接インスタンス化してマッピングを検証する。
 */
@DisplayName("ScheduleMapper 単体テスト")
class ScheduleMapperTest {

    private ScheduleMapperImpl mapper;

    @BeforeEach
    void setUp() {
        mapper = new ScheduleMapperImpl();
    }

    // ----------------------------------------
    // Helper: id / createdAt などを Reflection でセット
    // ----------------------------------------
    private void setBaseId(Object entity, Long id) throws Exception {
        Field field = entity.getClass().getSuperclass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    private void setBaseCreatedAt(Object entity, LocalDateTime dt) throws Exception {
        Field field = entity.getClass().getSuperclass().getDeclaredField("createdAt");
        field.setAccessible(true);
        field.set(entity, dt);
    }

    // EventSurveyEntity / ScheduleAttendanceReminderEntity / ScheduleCrossRefEntity は
    // BaseEntity を継承しないため getDeclaredField("id") を直接使う
    private void setDirectId(Object entity, Long id) throws Exception {
        Field field = entity.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    // ScheduleEntity ビルダーのユーティリティ
    private ScheduleEntity buildSchedule(Long id, EventType eventType, ScheduleStatus status) throws Exception {
        ScheduleEntity entity = ScheduleEntity.builder()
                .teamId(10L)
                .title("練習試合")
                .startAt(LocalDateTime.of(2026, 4, 1, 10, 0))
                .endAt(LocalDateTime.of(2026, 4, 1, 12, 0))
                .allDay(false)
                .eventType(eventType)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .minResponseRole(MinResponseRole.MEMBER_PLUS)
                .status(status)
                .attendanceRequired(true)
                .build();
        setBaseId(entity, id);
        return entity;
    }

    // ----------------------------------------
    // ScheduleEntity → ScheduleResponse
    // ----------------------------------------
    @Nested
    @DisplayName("toResponse")
    class ToResponse {

        @Test
        @DisplayName("正常系: eventType と status が name() に変換される")
        void eventTypeとstatus変換() throws Exception {
            ScheduleEntity entity = buildSchedule(1L, EventType.PRACTICE, ScheduleStatus.SCHEDULED);

            ScheduleResponse response = mapper.toResponse(entity);

            assertThat(response.getId()).isEqualTo(1L);
            assertThat(response.getEventType()).isEqualTo("PRACTICE");
            assertThat(response.getStatus()).isEqualTo("SCHEDULED");
            assertThat(response.getTitle()).isEqualTo("練習試合");
            assertThat(response.getAttendanceRequired()).isTrue();
        }

        @Test
        @DisplayName("正常系: MATCH / CANCELLED")
        void MATCH_CANCELLED変換() throws Exception {
            ScheduleEntity entity = buildSchedule(2L, EventType.MATCH, ScheduleStatus.CANCELLED);

            ScheduleResponse response = mapper.toResponse(entity);

            assertThat(response.getEventType()).isEqualTo("MATCH");
            assertThat(response.getStatus()).isEqualTo("CANCELLED");
        }

        @Test
        @DisplayName("正常系: リスト変換")
        void リスト変換() throws Exception {
            ScheduleEntity e1 = buildSchedule(1L, EventType.EVENT, ScheduleStatus.SCHEDULED);
            ScheduleEntity e2 = buildSchedule(2L, EventType.MEETING, ScheduleStatus.CANCELLED);

            List<ScheduleResponse> list = mapper.toResponseList(List.of(e1, e2));

            assertThat(list).hasSize(2);
            assertThat(list.get(0).getEventType()).isEqualTo("EVENT");
            assertThat(list.get(1).getEventType()).isEqualTo("MEETING");
        }
    }

    // ----------------------------------------
    // ScheduleEntity → ScheduleDetailResponse
    // ----------------------------------------
    @Nested
    @DisplayName("toDetailResponse")
    class ToDetailResponse {

        @Test
        @DisplayName("正常系: 詳細レスポンスへの変換（ignore フィールドは null）")
        void 詳細レスポンス変換() throws Exception {
            ScheduleEntity entity = buildSchedule(10L, EventType.PRACTICE, ScheduleStatus.SCHEDULED);

            ScheduleDetailResponse response = mapper.toDetailResponse(entity);

            assertThat(response.getId()).isEqualTo(10L);
            assertThat(response.getEventType()).isEqualTo("PRACTICE");
            assertThat(response.getStatus()).isEqualTo("SCHEDULED");
            assertThat(response.getVisibility()).isEqualTo("MEMBERS_ONLY");
            assertThat(response.getMinViewRole()).isEqualTo("MEMBER_PLUS");
            assertThat(response.getMinResponseRole()).isEqualTo("MEMBER_PLUS");
            // ignore フィールド
            assertThat(response.getSurveys()).isNull();
            assertThat(response.getReminders()).isNull();
            assertThat(response.getMyAttendance()).isNull();
            assertThat(response.getAttendanceSummary()).isNull();
            assertThat(response.getCrossInvitations()).isNull();
            assertThat(response.getRecurrenceRule()).isNull();
        }

        @Test
        @DisplayName("正常系: minResponseRole が null の場合も変換される")
        void minResponseRoleがnullの場合() throws Exception {
            ScheduleEntity entity = ScheduleEntity.builder()
                    .teamId(1L).title("テスト")
                    .startAt(LocalDateTime.of(2026, 5, 1, 9, 0))
                    .allDay(false)
                    .eventType(EventType.EVENT)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.ANYONE)
                    .status(ScheduleStatus.SCHEDULED)
                    .attendanceRequired(false)
                    .build();
            setBaseId(entity, 11L);

            ScheduleDetailResponse response = mapper.toDetailResponse(entity);

            assertThat(response.getMinViewRole()).isEqualTo("ANYONE");
            assertThat(response.getMinResponseRole()).isNull();
            assertThat(response.getCommentOption()).isNull();
        }
    }

    // ----------------------------------------
    // ScheduleAttendanceEntity → AttendanceResponse
    // ----------------------------------------
    @Nested
    @DisplayName("toAttendanceResponse")
    class ToAttendanceResponse {

        @Test
        @DisplayName("正常系: status が name() に変換される")
        void status変換() throws Exception {
            ScheduleAttendanceEntity entity = ScheduleAttendanceEntity.builder()
                    .scheduleId(1L).userId(5L)
                    .status(AttendanceStatus.ATTENDING)
                    .comment("参加します")
                    .respondedAt(LocalDateTime.of(2026, 3, 20, 10, 0))
                    .build();
            setBaseId(entity, 50L);

            AttendanceResponse response = mapper.toAttendanceResponse(entity);

            assertThat(response.getId()).isEqualTo(50L);
            assertThat(response.getUserId()).isEqualTo(5L);
            assertThat(response.getStatus()).isEqualTo("ATTENDING");
            assertThat(response.getComment()).isEqualTo("参加します");
        }

        @Test
        @DisplayName("正常系: ABSENT ステータス")
        void ABSENTステータス() throws Exception {
            ScheduleAttendanceEntity entity = ScheduleAttendanceEntity.builder()
                    .scheduleId(1L).userId(6L)
                    .status(AttendanceStatus.ABSENT)
                    .build();
            setBaseId(entity, 51L);

            AttendanceResponse response = mapper.toAttendanceResponse(entity);
            assertThat(response.getStatus()).isEqualTo("ABSENT");

            // リスト変換
            List<AttendanceResponse> list = mapper.toAttendanceResponseList(List.of(entity));
            assertThat(list).hasSize(1);
        }
    }

    // ----------------------------------------
    // EventSurveyEntity → EventSurveyResponse
    // ----------------------------------------
    @Nested
    @DisplayName("toSurveyResponse")
    class ToSurveyResponse {

        @Test
        @DisplayName("正常系: questionType が name() に変換される")
        void questionType変換() throws Exception {
            EventSurveyEntity entity = EventSurveyEntity.builder()
                    .scheduleId(1L)
                    .question("参加できますか？")
                    .questionType(SurveyQuestionType.BOOLEAN)
                    .isRequired(true)
                    .sortOrder(1)
                    .build();
            setDirectId(entity, 100L);

            EventSurveyResponse response = mapper.toSurveyResponse(entity);

            assertThat(response.getId()).isEqualTo(100L);
            assertThat(response.getQuestion()).isEqualTo("参加できますか？");
            assertThat(response.getQuestionType()).isEqualTo("BOOLEAN");
            assertThat(response.getIsRequired()).isTrue();
            assertThat(response.getSortOrder()).isEqualTo(1);
            assertThat(response.getOptions()).isNull(); // ignore
        }

        @Test
        @DisplayName("正常系: SELECT タイプ")
        void SELECTタイプ変換() throws Exception {
            EventSurveyEntity entity = EventSurveyEntity.builder()
                    .scheduleId(1L).question("役割は？")
                    .questionType(SurveyQuestionType.SELECT)
                    .isRequired(false).sortOrder(2).build();
            setDirectId(entity, 101L);

            EventSurveyResponse response = mapper.toSurveyResponse(entity);
            assertThat(response.getQuestionType()).isEqualTo("SELECT");

            // リスト変換
            List<EventSurveyResponse> list = mapper.toSurveyResponseList(List.of(entity));
            assertThat(list).hasSize(1);
        }
    }

    // ----------------------------------------
    // ScheduleAttendanceReminderEntity → ReminderResponse
    // ----------------------------------------
    @Nested
    @DisplayName("toReminderResponse")
    class ToReminderResponse {

        @Test
        @DisplayName("正常系: リマインダーレスポンス変換")
        void リマインダーレスポンス変換() throws Exception {
            LocalDateTime remindAt = LocalDateTime.of(2026, 4, 1, 8, 0);
            ScheduleAttendanceReminderEntity entity = ScheduleAttendanceReminderEntity.builder()
                    .scheduleId(1L).remindAt(remindAt).isSent(false).build();
            setDirectId(entity, 200L);

            ReminderResponse response = mapper.toReminderResponse(entity);

            assertThat(response.getId()).isEqualTo(200L);
            assertThat(response.getRemindAt()).isEqualTo(remindAt);
            assertThat(response.getIsSent()).isFalse();
            assertThat(response.getSentAt()).isNull();
        }

        @Test
        @DisplayName("正常系: 送信済みリマインダー")
        void 送信済みリマインダー変換() throws Exception {
            LocalDateTime remindAt = LocalDateTime.of(2026, 4, 1, 8, 0);
            LocalDateTime sentAt = LocalDateTime.of(2026, 4, 1, 8, 0);
            ScheduleAttendanceReminderEntity entity = ScheduleAttendanceReminderEntity.builder()
                    .scheduleId(1L).remindAt(remindAt).isSent(true).sentAt(sentAt).build();
            setDirectId(entity, 201L);

            ReminderResponse response = mapper.toReminderResponse(entity);
            assertThat(response.getIsSent()).isTrue();
            assertThat(response.getSentAt()).isEqualTo(sentAt);

            // リスト変換
            List<ReminderResponse> list = mapper.toReminderResponseList(List.of(entity));
            assertThat(list).hasSize(1);
        }
    }

    // ----------------------------------------
    // ScheduleCrossRefEntity → CrossRefResponse
    // ----------------------------------------
    @Nested
    @DisplayName("toCrossRefResponse")
    class ToCrossRefResponse {

        @Test
        @DisplayName("正常系: targetType と status が name() に変換される")
        void targetTypeとstatus変換() throws Exception {
            ScheduleCrossRefEntity entity = ScheduleCrossRefEntity.builder()
                    .sourceScheduleId(1L)
                    .targetType(CrossRefTargetType.TEAM)
                    .targetId(20L)
                    .targetScheduleId(30L)
                    .invitedBy(5L)
                    .status(CrossRefStatus.ACCEPTED)
                    .message("招待します")
                    .respondedAt(LocalDateTime.of(2026, 3, 10, 12, 0))
                    .build();
            setDirectId(entity, 300L);

            CrossRefResponse response = mapper.toCrossRefResponse(entity);

            assertThat(response.getId()).isEqualTo(300L);
            assertThat(response.getSourceScheduleId()).isEqualTo(1L);
            assertThat(response.getTargetType()).isEqualTo("TEAM");
            assertThat(response.getTargetId()).isEqualTo(20L);
            assertThat(response.getTargetScheduleId()).isEqualTo(30L);
            assertThat(response.getStatus()).isEqualTo("ACCEPTED");
            assertThat(response.getMessage()).isEqualTo("招待します");
        }

        @Test
        @DisplayName("正常系: PENDING ステータス")
        void PENDINGステータス変換() throws Exception {
            ScheduleCrossRefEntity entity = ScheduleCrossRefEntity.builder()
                    .sourceScheduleId(2L)
                    .targetType(CrossRefTargetType.TEAM)
                    .targetId(21L)
                    .status(CrossRefStatus.PENDING)
                    .build();
            setDirectId(entity, 301L);

            CrossRefResponse response = mapper.toCrossRefResponse(entity);
            assertThat(response.getStatus()).isEqualTo("PENDING");

            // リスト変換
            List<CrossRefResponse> list = mapper.toCrossRefResponseList(List.of(entity));
            assertThat(list).hasSize(1);
        }
    }

    // ----------------------------------------
    // EventSurveyResponseEntity → SurveyResponseDetailResponse
    // ----------------------------------------
    @Nested
    @DisplayName("toSurveyResponseDetailResponse")
    class ToSurveyResponseDetailResponse {

        @Test
        @DisplayName("正常系: eventSurveyId が surveyId にマッピングされる")
        void eventSurveyIdがsurveyIdにマッピング() throws Exception {
            EventSurveyResponseEntity entity = EventSurveyResponseEntity.builder()
                    .eventSurveyId(100L).userId(5L)
                    .answerText("はい").build();
            setBaseId(entity, 400L);

            SurveyResponseDetailResponse response = mapper.toSurveyResponseDetailResponse(entity);

            assertThat(response.getSurveyId()).isEqualTo(100L);
            assertThat(response.getUserId()).isEqualTo(5L);
            assertThat(response.getAnswerText()).isEqualTo("はい");
            assertThat(response.getAnswerOptions()).isNull(); // ignore
        }

        @Test
        @DisplayName("正常系: answerText が null の場合")
        void answerTextがnull() throws Exception {
            EventSurveyResponseEntity entity = EventSurveyResponseEntity.builder()
                    .eventSurveyId(101L).userId(6L).build();
            setBaseId(entity, 401L);

            SurveyResponseDetailResponse response = mapper.toSurveyResponseDetailResponse(entity);

            assertThat(response.getSurveyId()).isEqualTo(101L);
            assertThat(response.getAnswerText()).isNull();
        }
    }
}
