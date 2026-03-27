package com.mannschaft.app.search.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.facility.repository.FacilityBookingRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.queue.TicketSource;
import com.mannschaft.app.queue.entity.QueueTicketEntity;
import com.mannschaft.app.queue.repository.QueueTicketRepository;
import com.mannschaft.app.safetycheck.SafetyCheckScopeType;
import com.mannschaft.app.safetycheck.SafetyCheckStatus;
import com.mannschaft.app.safetycheck.entity.SafetyCheckEntity;
import com.mannschaft.app.safetycheck.repository.SafetyCheckRepository;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.search.dto.SearchResultResponse;
import com.mannschaft.app.shift.ShiftPeriodType;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;

/**
 * {@link GlobalSearchService} の追加単体テスト。
 * 実データを含む検索結果の変換ロジックを検証する。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GlobalSearchService 追加テスト")
class GlobalSearchServiceAdditionalTest {

    @Mock
    private ScheduleRepository scheduleRepository;

    @Mock
    private EventRepository eventRepository;

    @Mock
    private FacilityBookingRepository facilityBookingRepository;

    @Mock
    private ShiftScheduleRepository shiftScheduleRepository;

    @Mock
    private SafetyCheckRepository safetyCheckRepository;

    @Mock
    private QueueTicketRepository queueTicketRepository;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private GlobalSearchService globalSearchService;

    private static final Long USER_ID = 100L;

    private void stubEmptyResults(String query) {
        given(scheduleRepository.searchByKeyword(eq(query), any(Pageable.class))).willReturn(List.of());
        given(eventRepository.searchByKeyword(eq(query), any(Pageable.class))).willReturn(List.of());
        given(facilityBookingRepository.searchByKeyword(eq(query), any(Pageable.class))).willReturn(List.of());
        given(shiftScheduleRepository.searchByKeyword(eq(query), any(Pageable.class))).willReturn(List.of());
        given(safetyCheckRepository.searchByKeyword(eq(query), any(Pageable.class))).willReturn(List.of());
        given(queueTicketRepository.searchByKeyword(eq(query), any(Pageable.class))).willReturn(List.of());
        given(teamRepository.searchByKeyword(eq(query), any(Pageable.class))).willReturn(new PageImpl<>(List.of()));
        given(organizationRepository.searchByKeyword(eq(query), any(Pageable.class))).willReturn(new PageImpl<>(List.of()));
        given(userRepository.searchByKeyword(eq(query), any(Pageable.class))).willReturn(List.of());
    }

    // ========================================
    // search - 実データを含むテスト
    // ========================================

    @Nested
    @DisplayName("search - 実データ変換テスト")
    class SearchWithRealData {

        @Test
        @DisplayName("正常系: スケジュール実データが正しいフィールドでマッピングされる")
        void search_スケジュール実データ_正しいフィールド() {
            // Given
            ScheduleEntity schedule = ScheduleEntity.builder()
                    .teamId(10L)
                    .title("春季大会")
                    .location("東京体育館")
                    .startAt(LocalDateTime.of(2026, 5, 1, 10, 0))
                    .eventType(EventType.MATCH)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.MEMBER_PLUS)
                    .status(ScheduleStatus.SCHEDULED)
                    .build();
            ReflectionTestUtils.setField(schedule, "id", 1L);

            given(scheduleRepository.searchByKeyword(eq("春季"), any(Pageable.class))).willReturn(List.of(schedule));
            given(eventRepository.searchByKeyword(eq("春季"), any(Pageable.class))).willReturn(List.of());
            given(facilityBookingRepository.searchByKeyword(eq("春季"), any(Pageable.class))).willReturn(List.of());
            given(shiftScheduleRepository.searchByKeyword(eq("春季"), any(Pageable.class))).willReturn(List.of());
            given(safetyCheckRepository.searchByKeyword(eq("春季"), any(Pageable.class))).willReturn(List.of());
            given(queueTicketRepository.searchByKeyword(eq("春季"), any(Pageable.class))).willReturn(List.of());
            given(teamRepository.searchByKeyword(eq("春季"), any(Pageable.class))).willReturn(new PageImpl<>(List.of()));
            given(organizationRepository.searchByKeyword(eq("春季"), any(Pageable.class))).willReturn(new PageImpl<>(List.of()));
            given(userRepository.searchByKeyword(eq("春季"), any(Pageable.class))).willReturn(List.of());

            // When
            SearchResultResponse result = globalSearchService.search("春季", USER_ID);

            // Then
            assertThat(result.getCounts().get("schedules")).isEqualTo(1L);
            List<Map<String, Object>> schedules = result.getResults().get("schedules");
            assertThat(schedules).hasSize(1);
            assertThat(schedules.get(0).get("id")).isEqualTo(1L);
            assertThat(schedules.get(0).get("title")).isEqualTo("春季大会");
            assertThat(schedules.get(0).get("location")).isEqualTo("東京体育館");
        }

        @Test
        @DisplayName("正常系: locationがnullのスケジュールは空文字で返る")
        void search_スケジュールlocationがnull_空文字() {
            // Given
            ScheduleEntity schedule = ScheduleEntity.builder()
                    .teamId(10L)
                    .title("ミーティング")
                    .location(null)
                    .startAt(LocalDateTime.of(2026, 5, 1, 10, 0))
                    .eventType(EventType.MEETING)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.MEMBER_PLUS)
                    .status(ScheduleStatus.SCHEDULED)
                    .build();
            ReflectionTestUtils.setField(schedule, "id", 2L);

            given(scheduleRepository.searchByKeyword(eq("ミーティング"), any(Pageable.class))).willReturn(List.of(schedule));
            given(eventRepository.searchByKeyword(eq("ミーティング"), any(Pageable.class))).willReturn(List.of());
            given(facilityBookingRepository.searchByKeyword(eq("ミーティング"), any(Pageable.class))).willReturn(List.of());
            given(shiftScheduleRepository.searchByKeyword(eq("ミーティング"), any(Pageable.class))).willReturn(List.of());
            given(safetyCheckRepository.searchByKeyword(eq("ミーティング"), any(Pageable.class))).willReturn(List.of());
            given(queueTicketRepository.searchByKeyword(eq("ミーティング"), any(Pageable.class))).willReturn(List.of());
            given(teamRepository.searchByKeyword(eq("ミーティング"), any(Pageable.class))).willReturn(new PageImpl<>(List.of()));
            given(organizationRepository.searchByKeyword(eq("ミーティング"), any(Pageable.class))).willReturn(new PageImpl<>(List.of()));
            given(userRepository.searchByKeyword(eq("ミーティング"), any(Pageable.class))).willReturn(List.of());

            // When
            SearchResultResponse result = globalSearchService.search("ミーティング", USER_ID);

            // Then
            List<Map<String, Object>> schedules = result.getResults().get("schedules");
            assertThat(schedules.get(0).get("location")).isEqualTo("");
        }

        @Test
        @DisplayName("正常系: チーム実データが正しいフィールドでマッピングされる")
        void search_チーム実データ_正しいフィールド() {
            // Given
            TeamEntity team = TeamEntity.builder()
                    .name("テストFC")
                    .build();
            ReflectionTestUtils.setField(team, "id", 5L);

            stubEmptyResults("FC");
            given(teamRepository.searchByKeyword(eq("FC"), any(Pageable.class))).willReturn(new PageImpl<>(List.of(team)));

            // When
            SearchResultResponse result = globalSearchService.search("FC", USER_ID);

            // Then
            assertThat(result.getCounts().get("teams")).isEqualTo(1L);
            List<Map<String, Object>> teams = result.getResults().get("teams");
            assertThat(teams).hasSize(1);
            assertThat(teams.get(0).get("id")).isEqualTo(5L);
            assertThat(teams.get(0).get("name")).isEqualTo("テストFC");
        }

        @Test
        @DisplayName("正常系: 組織実データが正しいフィールドでマッピングされる")
        void search_組織実データ_正しいフィールド() {
            // Given
            OrganizationEntity org = OrganizationEntity.builder()
                    .name("テスト協会")
                    .build();
            ReflectionTestUtils.setField(org, "id", 7L);

            stubEmptyResults("協会");
            given(organizationRepository.searchByKeyword(eq("協会"), any(Pageable.class))).willReturn(new PageImpl<>(List.of(org)));

            // When
            SearchResultResponse result = globalSearchService.search("協会", USER_ID);

            // Then
            assertThat(result.getCounts().get("organizations")).isEqualTo(1L);
            List<Map<String, Object>> orgs = result.getResults().get("organizations");
            assertThat(orgs).hasSize(1);
            assertThat(orgs.get(0).get("id")).isEqualTo(7L);
            assertThat(orgs.get(0).get("name")).isEqualTo("テスト協会");
        }

        @Test
        @DisplayName("正常系: ユーザー実データが正しいフィールドでマッピングされる")
        void search_ユーザー実データ_正しいフィールド() {
            // Given
            UserEntity user = UserEntity.builder()
                    .email("yamada@example.com")
                    .passwordHash("hash")
                    .lastName("山田")
                    .firstName("太郎")
                    .displayName("yamada_taro")
                    .locale("ja")
                    .timezone("Asia/Tokyo")
                    .status(UserEntity.UserStatus.ACTIVE)
                    .isSearchable(true)
                    .build();
            ReflectionTestUtils.setField(user, "id", 99L);

            stubEmptyResults("yamada");
            given(userRepository.searchByKeyword(eq("yamada"), any(Pageable.class))).willReturn(List.of(user));

            // When
            SearchResultResponse result = globalSearchService.search("yamada", USER_ID);

            // Then
            assertThat(result.getCounts().get("users")).isEqualTo(1L);
            List<Map<String, Object>> users = result.getResults().get("users");
            assertThat(users).hasSize(1);
            assertThat(users.get(0).get("id")).isEqualTo(99L);
            assertThat(users.get(0).get("displayName")).isEqualTo("yamada_taro");
        }

        @Test
        @DisplayName("正常系: シフト実データが正しいフィールドでマッピングされる")
        void search_シフト実データ_正しいフィールド() {
            // Given
            ShiftScheduleEntity shift = ShiftScheduleEntity.builder()
                    .teamId(10L)
                    .title("5月シフト")
                    .periodType(ShiftPeriodType.MONTHLY)
                    .startDate(LocalDate.of(2026, 5, 1))
                    .endDate(LocalDate.of(2026, 5, 31))
                    .build();
            ReflectionTestUtils.setField(shift, "id", 3L);

            stubEmptyResults("5月");
            given(shiftScheduleRepository.searchByKeyword(eq("5月"), any(Pageable.class))).willReturn(List.of(shift));

            // When
            SearchResultResponse result = globalSearchService.search("5月", USER_ID);

            // Then
            assertThat(result.getCounts().get("shifts")).isEqualTo(1L);
            List<Map<String, Object>> shifts = result.getResults().get("shifts");
            assertThat(shifts).hasSize(1);
            assertThat(shifts.get(0).get("id")).isEqualTo(3L);
            assertThat(shifts.get(0).get("title")).isEqualTo("5月シフト");
        }

        @Test
        @DisplayName("正常系: 安否確認実データが正しいフィールドでマッピングされる")
        void search_安否確認実データ_正しいフィールド() {
            // Given
            SafetyCheckEntity safetyCheck = SafetyCheckEntity.builder()
                    .scopeType(SafetyCheckScopeType.TEAM)
                    .scopeId(10L)
                    .title("緊急安否確認")
                    .message("地震が発生しました")
                    .isDrill(false)
                    .status(SafetyCheckStatus.ACTIVE)
                    .build();
            ReflectionTestUtils.setField(safetyCheck, "id", 4L);

            stubEmptyResults("緊急");
            given(safetyCheckRepository.searchByKeyword(eq("緊急"), any(Pageable.class))).willReturn(List.of(safetyCheck));

            // When
            SearchResultResponse result = globalSearchService.search("緊急", USER_ID);

            // Then
            assertThat(result.getCounts().get("safetyChecks")).isEqualTo(1L);
            List<Map<String, Object>> safetyChecks = result.getResults().get("safetyChecks");
            assertThat(safetyChecks).hasSize(1);
            assertThat(safetyChecks.get(0).get("id")).isEqualTo(4L);
            assertThat(safetyChecks.get(0).get("title")).isEqualTo("緊急安否確認");
        }

        @Test
        @DisplayName("正常系: キューguestNameがnullの場合は空文字で返る")
        void search_キューguestNameがnull_空文字() {
            // Given
            QueueTicketEntity queue = QueueTicketEntity.builder()
                    .categoryId(1L)
                    .counterId(1L)
                    .ticketNumber("A001")
                    .guestName(null)
                    .source(TicketSource.QR)
                    .build();
            ReflectionTestUtils.setField(queue, "id", 6L);

            stubEmptyResults("A001");
            given(queueTicketRepository.searchByKeyword(eq("A001"), any(Pageable.class))).willReturn(List.of(queue));

            // When
            SearchResultResponse result = globalSearchService.search("A001", USER_ID);

            // Then
            List<Map<String, Object>> queues = result.getResults().get("queues");
            assertThat(queues).hasSize(1);
            assertThat(queues.get(0).get("ticketNumber")).isEqualTo("A001");
            assertThat(queues.get(0).get("guestName")).isEqualTo("");
        }

        @Test
        @DisplayName("正常系: キューguestNameがある場合はその値で返る")
        void search_キューguestNameあり_正しい値() {
            // Given
            QueueTicketEntity queue = QueueTicketEntity.builder()
                    .categoryId(1L)
                    .counterId(1L)
                    .ticketNumber("B002")
                    .guestName("山田花子")
                    .source(TicketSource.ONLINE)
                    .build();
            ReflectionTestUtils.setField(queue, "id", 7L);

            stubEmptyResults("山田");
            given(queueTicketRepository.searchByKeyword(eq("山田"), any(Pageable.class))).willReturn(List.of(queue));

            // When
            SearchResultResponse result = globalSearchService.search("山田", USER_ID);

            // Then
            List<Map<String, Object>> queues = result.getResults().get("queues");
            assertThat(queues.get(0).get("guestName")).isEqualTo("山田花子");
        }

        @Test
        @DisplayName("正常系: 複数カテゴリにまたがる検索結果が正しく集計される")
        void search_複数カテゴリ実データ_集計が正しい() {
            // Given: スケジュールとチームに1件ずつ
            ScheduleEntity schedule = ScheduleEntity.builder()
                    .teamId(10L)
                    .title("テスト練習")
                    .startAt(LocalDateTime.of(2026, 5, 1, 10, 0))
                    .eventType(EventType.PRACTICE)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.MEMBER_PLUS)
                    .status(ScheduleStatus.SCHEDULED)
                    .build();
            ReflectionTestUtils.setField(schedule, "id", 10L);

            TeamEntity team = TeamEntity.builder()
                    .name("テストFC")
                    .build();
            ReflectionTestUtils.setField(team, "id", 20L);

            given(scheduleRepository.searchByKeyword(eq("テスト"), any(Pageable.class))).willReturn(List.of(schedule));
            given(eventRepository.searchByKeyword(eq("テスト"), any(Pageable.class))).willReturn(List.of());
            given(facilityBookingRepository.searchByKeyword(eq("テスト"), any(Pageable.class))).willReturn(List.of());
            given(shiftScheduleRepository.searchByKeyword(eq("テスト"), any(Pageable.class))).willReturn(List.of());
            given(safetyCheckRepository.searchByKeyword(eq("テスト"), any(Pageable.class))).willReturn(List.of());
            given(queueTicketRepository.searchByKeyword(eq("テスト"), any(Pageable.class))).willReturn(List.of());
            given(teamRepository.searchByKeyword(eq("テスト"), any(Pageable.class))).willReturn(new PageImpl<>(List.of(team)));
            given(organizationRepository.searchByKeyword(eq("テスト"), any(Pageable.class))).willReturn(new PageImpl<>(List.of()));
            given(userRepository.searchByKeyword(eq("テスト"), any(Pageable.class))).willReturn(List.of());

            // When
            SearchResultResponse result = globalSearchService.search("テスト", USER_ID);

            // Then
            assertThat(result.getCounts().get("schedules")).isEqualTo(1L);
            assertThat(result.getCounts().get("teams")).isEqualTo(1L);
            assertThat(result.getCounts().get("events")).isEqualTo(0L);
            assertThat(result.getCounts().get("users")).isEqualTo(0L);
        }
    }
}
