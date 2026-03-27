package com.mannschaft.app.schedule;

import com.mannschaft.app.schedule.dto.PersonalScheduleResponse;
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
 * {@link PersonalScheduleMapper} (MapStruct生成実装) の単体テスト。
 * PersonalScheduleMapperImpl を直接インスタンス化してマッピングを検証する。
 */
@DisplayName("PersonalScheduleMapper 単体テスト")
class PersonalScheduleMapperTest {

    private PersonalScheduleMapperImpl mapper;

    @BeforeEach
    void setUp() {
        mapper = new PersonalScheduleMapperImpl();
    }

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

    private void setBaseUpdatedAt(Object entity, LocalDateTime dt) throws Exception {
        Field field = entity.getClass().getSuperclass().getDeclaredField("updatedAt");
        field.setAccessible(true);
        field.set(entity, dt);
    }

    // ----------------------------------------
    // ScheduleEntity → PersonalScheduleResponse
    // ----------------------------------------
    @Nested
    @DisplayName("toResponse")
    class ToResponse {

        @Test
        @DisplayName("正常系: googleCalendarEventId が null の場合 googleSynced=false")
        void googleCalendarEventIdがnullのときgoogleSyncedFalse() throws Exception {
            ScheduleEntity entity = ScheduleEntity.builder()
                    .userId(1L).title("個人予定")
                    .description("メモ").location("自宅")
                    .startAt(LocalDateTime.of(2026, 4, 10, 10, 0))
                    .endAt(LocalDateTime.of(2026, 4, 10, 11, 0))
                    .allDay(false)
                    .eventType(EventType.EVENT)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.ANYONE)
                    .status(ScheduleStatus.SCHEDULED)
                    .attendanceRequired(false)
                    .color("#FF0000")
                    .build();
            setBaseId(entity, 1L);
            LocalDateTime now = LocalDateTime.of(2026, 1, 1, 0, 0);
            setBaseCreatedAt(entity, now);
            setBaseUpdatedAt(entity, now);

            PersonalScheduleResponse response = mapper.toResponse(entity);

            assertThat(response.getId()).isEqualTo(1L);
            assertThat(response.getTitle()).isEqualTo("個人予定");
            assertThat(response.getDescription()).isEqualTo("メモ");
            assertThat(response.getLocation()).isEqualTo("自宅");
            assertThat(response.getEventType()).isEqualTo("EVENT");
            assertThat(response.getStatus()).isEqualTo("SCHEDULED");
            assertThat(response.getColor()).isEqualTo("#FF0000");
            assertThat(response.isGoogleSynced()).isFalse();
            // ignore フィールド
            assertThat(response.getRecurrenceRule()).isNull();
            assertThat(response.getReminders()).isNull();
            assertThat(response.getCreatedAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("正常系: googleCalendarEventId が設定されている場合 googleSynced=true")
        void googleCalendarEventIdがあるときgoogleSyncedTrue() throws Exception {
            ScheduleEntity entity = ScheduleEntity.builder()
                    .userId(2L).title("Google同期予定")
                    .startAt(LocalDateTime.of(2026, 5, 1, 9, 0))
                    .allDay(false)
                    .eventType(EventType.MEETING)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.MEMBER_PLUS)
                    .status(ScheduleStatus.SCHEDULED)
                    .attendanceRequired(false)
                    .googleCalendarEventId("google_event_id_123")
                    .build();
            setBaseId(entity, 2L);

            PersonalScheduleResponse response = mapper.toResponse(entity);

            assertThat(response.getEventType()).isEqualTo("MEETING");
            assertThat(response.isGoogleSynced()).isTrue();
        }

        @Test
        @DisplayName("正常系: allDay=true のスケジュール")
        void allDayスケジュール() throws Exception {
            ScheduleEntity entity = ScheduleEntity.builder()
                    .userId(3L).title("終日予定")
                    .startAt(LocalDateTime.of(2026, 6, 1, 0, 0))
                    .allDay(true)
                    .eventType(EventType.PRACTICE)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.ANYONE)
                    .status(ScheduleStatus.CANCELLED)
                    .attendanceRequired(false)
                    .build();
            setBaseId(entity, 3L);

            PersonalScheduleResponse response = mapper.toResponse(entity);

            assertThat(response.getAllDay()).isTrue();
            assertThat(response.getStatus()).isEqualTo("CANCELLED");
            assertThat(response.isGoogleSynced()).isFalse();
        }

        @Test
        @DisplayName("正常系: リスト変換")
        void リスト変換() throws Exception {
            ScheduleEntity e1 = ScheduleEntity.builder()
                    .userId(1L).title("予定1")
                    .startAt(LocalDateTime.of(2026, 4, 1, 10, 0))
                    .allDay(false).eventType(EventType.EVENT)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.ANYONE)
                    .status(ScheduleStatus.SCHEDULED)
                    .attendanceRequired(false).build();
            ScheduleEntity e2 = ScheduleEntity.builder()
                    .userId(1L).title("予定2")
                    .startAt(LocalDateTime.of(2026, 4, 2, 10, 0))
                    .allDay(true).eventType(EventType.MEETING)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.ANYONE)
                    .status(ScheduleStatus.SCHEDULED)
                    .attendanceRequired(false).build();
            setBaseId(e1, 1L);
            setBaseId(e2, 2L);

            List<PersonalScheduleResponse> list = mapper.toResponseList(List.of(e1, e2));

            assertThat(list).hasSize(2);
            assertThat(list.get(0).getEventType()).isEqualTo("EVENT");
            assertThat(list.get(1).getEventType()).isEqualTo("MEETING");
        }
    }
}
