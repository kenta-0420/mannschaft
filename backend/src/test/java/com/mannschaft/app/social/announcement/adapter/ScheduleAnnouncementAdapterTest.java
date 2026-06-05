package com.mannschaft.app.social.announcement.adapter;

import com.mannschaft.app.schedule.dto.CreateScheduleRequest;
import com.mannschaft.app.schedule.dto.ScheduleResponse;
import com.mannschaft.app.schedule.service.ScheduleService;
import com.mannschaft.app.social.announcement.AnnouncementContentRequest;
import com.mannschaft.app.social.announcement.AnnouncementSourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ScheduleAnnouncementAdapter} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleAnnouncementAdapter 単体テスト")
class ScheduleAnnouncementAdapterTest {

    @Mock
    private ScheduleService scheduleService;

    @InjectMocks
    private ScheduleAnnouncementAdapter adapter;

    // ──────────────────────────────────────────────────────────────────────────
    // テストデータ定数
    // ──────────────────────────────────────────────────────────────────────────

    private static final Long SCOPE_ID = 10L;
    private static final Long USER_ID = 1L;
    private static final Long SCHEDULE_ID = 100L;
    private static final OffsetDateTime START_AT = OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.ofHours(9));
    private static final OffsetDateTime END_AT = OffsetDateTime.of(2026, 6, 1, 12, 0, 0, 0, ZoneOffset.ofHours(9));

    // ──────────────────────────────────────────────────────────────────────────
    // getSourceType
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getSourceType")
    class GetSourceType {

        @Test
        @DisplayName("SCHEDULE を返すこと")
        void returnsScheduleSourceType() {
            // when
            AnnouncementSourceType result = adapter.getSourceType();

            // then
            assertThat(result).isEqualTo(AnnouncementSourceType.SCHEDULE);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // createContent
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createContent")
    class CreateContent {

        @Test
        @DisplayName("startAt が設定されている場合、ScheduleService.createSchedule() が呼ばれ、スケジュール ID が返ること")
        void createsScheduleWithStartAt() {
            // given
            AnnouncementContentRequest content = AnnouncementContentRequest.builder()
                    .title("テスト告知スケジュール")
                    .description("スケジュールの説明")
                    .location("東京体育館")
                    .startAt(START_AT)
                    .endAt(END_AT)
                    .allDay(false)
                    .build();

            ScheduleResponse mockResponse = buildScheduleResponse(SCHEDULE_ID);
            given(scheduleService.createSchedule(any(CreateScheduleRequest.class), anyLong(), anyString(), anyLong()))
                    .willReturn(mockResponse);

            // when
            Long result = adapter.createContent(content, "TEAM", SCOPE_ID, "MEMBERS_AND_ABOVE", USER_ID);

            // then
            assertThat(result).isEqualTo(SCHEDULE_ID);
        }

        @Test
        @DisplayName("location が content.getLocation() からそのまま渡されること")
        void passesLocationFromContent() {
            // given
            String expectedLocation = "大阪なんば球場";
            AnnouncementContentRequest content = AnnouncementContentRequest.builder()
                    .title("ロケーション確認テスト")
                    .location(expectedLocation)
                    .startAt(START_AT)
                    .build();

            ScheduleResponse mockResponse = buildScheduleResponse(SCHEDULE_ID);
            given(scheduleService.createSchedule(any(CreateScheduleRequest.class), anyLong(), anyString(), anyLong()))
                    .willReturn(mockResponse);

            ArgumentCaptor<CreateScheduleRequest> captor =
                    ArgumentCaptor.forClass(CreateScheduleRequest.class);

            // when
            adapter.createContent(content, "TEAM", SCOPE_ID, "MEMBERS_AND_ABOVE", USER_ID);

            // then
            verify(scheduleService).createSchedule(captor.capture(), anyLong(), anyString(), anyLong());
            assertThat(captor.getValue().getLocation()).isEqualTo(expectedLocation);
        }

        @Test
        @DisplayName("allDay = true の場合でも正常にスケジュールが作成されること")
        void createsScheduleWithAllDayTrue() {
            // given
            AnnouncementContentRequest content = AnnouncementContentRequest.builder()
                    .title("終日イベント")
                    .startAt(START_AT)
                    .allDay(true)
                    .build();

            ScheduleResponse mockResponse = buildScheduleResponse(SCHEDULE_ID);
            given(scheduleService.createSchedule(any(CreateScheduleRequest.class), anyLong(), anyString(), anyLong()))
                    .willReturn(mockResponse);

            ArgumentCaptor<CreateScheduleRequest> captor =
                    ArgumentCaptor.forClass(CreateScheduleRequest.class);

            // when
            Long result = adapter.createContent(content, "TEAM", SCOPE_ID, "MEMBERS_AND_ABOVE", USER_ID);

            // then
            verify(scheduleService).createSchedule(captor.capture(), anyLong(), anyString(), anyLong());
            assertThat(captor.getValue().getAllDay()).isTrue();
            assertThat(result).isEqualTo(SCHEDULE_ID);
        }

        @Test
        @DisplayName("startAt が null の場合、IllegalArgumentException が発生すること")
        void throwsExceptionWhenStartAtIsNull() {
            // given
            AnnouncementContentRequest content = AnnouncementContentRequest.builder()
                    .title("startAt なしスケジュール")
                    .startAt(null)
                    .build();

            // when / then
            assertThatThrownBy(() ->
                    adapter.createContent(content, "TEAM", SCOPE_ID, "MEMBERS_AND_ABOVE", USER_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("start_at");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // buildContentUrl
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildContentUrl")
    class BuildContentUrl {

        @Test
        @DisplayName("TEAM スコープの場合、/teams/{scopeId}/schedules/{contentId} 形式になること")
        void buildsTeamScopeUrl() {
            // given
            Long scopeId = 5L;
            Long contentId = 42L;

            // when
            String url = adapter.buildContentUrl("TEAM", scopeId, contentId);

            // then
            assertThat(url).isEqualTo("/teams/5/schedules/42");
        }

        @Test
        @DisplayName("ORGANIZATION スコープの場合、/organizations/{scopeId}/schedules/{contentId} 形式になること")
        void buildsOrganizationScopeUrl() {
            // given
            Long scopeId = 99L;
            Long contentId = 77L;

            // when
            String url = adapter.buildContentUrl("ORGANIZATION", scopeId, contentId);

            // then
            assertThat(url).isEqualTo("/organizations/99/schedules/77");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // ヘルパー
    // ──────────────────────────────────────────────────────────────────────────

    private ScheduleResponse buildScheduleResponse(Long id) {
        return ScheduleResponse.builder()
                .id(id)
                .content(new ScheduleResponse.ScheduleContentDto(
                        "テストスケジュール", "SCHEDULED", "NORMAL", null, false))
                .time(new ScheduleResponse.ScheduleTimeDto(START_AT.toLocalDateTime(), END_AT.toLocalDateTime(), false))
                .scope(new ScheduleResponse.ScheduleScopeDto(null, null))
                .academic(new ScheduleResponse.ScheduleAcademicDto(null, null, null))
                .audit(new ScheduleResponse.ScheduleAuditDto(LocalDateTime.now(), null))
                .build();
    }
}
