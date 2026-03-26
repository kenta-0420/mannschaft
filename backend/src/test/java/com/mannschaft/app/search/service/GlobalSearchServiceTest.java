package com.mannschaft.app.search.service;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.facility.repository.FacilityBookingRepository;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.queue.repository.QueueTicketRepository;
import com.mannschaft.app.safetycheck.repository.SafetyCheckRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.search.dto.SearchResultResponse;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link GlobalSearchService} の単体テスト。
 * 9種別横断検索を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GlobalSearchService 単体テスト")
class GlobalSearchServiceTest {

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

    // ========================================
    // search
    // ========================================
    @Nested
    @DisplayName("search")
    class Search {

        @Test
        @DisplayName("正常系: 9種別の横断検索結果を取得できる")
        void 横断検索結果を取得できる() {
            // given
            given(scheduleRepository.searchByKeyword(eq("テスト"), any(Pageable.class)))
                    .willReturn(List.of());
            given(eventRepository.searchByKeyword(eq("テスト"), any(Pageable.class)))
                    .willReturn(List.of());
            given(facilityBookingRepository.searchByKeyword(eq("テスト"), any(Pageable.class)))
                    .willReturn(List.of());
            given(shiftScheduleRepository.searchByKeyword(eq("テスト"), any(Pageable.class)))
                    .willReturn(List.of());
            given(safetyCheckRepository.searchByKeyword(eq("テスト"), any(Pageable.class)))
                    .willReturn(List.of());
            given(queueTicketRepository.searchByKeyword(eq("テスト"), any(Pageable.class)))
                    .willReturn(List.of());
            given(teamRepository.searchByKeyword(eq("テスト"), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of()));
            given(organizationRepository.searchByKeyword(eq("テスト"), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of()));
            given(userRepository.searchByKeyword(eq("テスト"), any(Pageable.class)))
                    .willReturn(List.of());

            // when
            SearchResultResponse result = globalSearchService.search("テスト", USER_ID);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getQuery()).isEqualTo("テスト");
            assertThat(result.getResults()).containsKeys(
                    "schedules", "events", "reservations", "shifts",
                    "safetyChecks", "queues", "teams", "organizations", "users");
            assertThat(result.getCounts()).hasSize(9);
            assertThat(result.getExecutionTimeMs()).isGreaterThanOrEqualTo(0L);
        }

        @Test
        @DisplayName("正常系: 全リポジトリが呼び出される")
        void 全リポジトリが呼び出される() {
            // given
            given(scheduleRepository.searchByKeyword(any(), any(Pageable.class))).willReturn(List.of());
            given(eventRepository.searchByKeyword(any(), any(Pageable.class))).willReturn(List.of());
            given(facilityBookingRepository.searchByKeyword(any(), any(Pageable.class))).willReturn(List.of());
            given(shiftScheduleRepository.searchByKeyword(any(), any(Pageable.class))).willReturn(List.of());
            given(safetyCheckRepository.searchByKeyword(any(), any(Pageable.class))).willReturn(List.of());
            given(queueTicketRepository.searchByKeyword(any(), any(Pageable.class))).willReturn(List.of());
            given(teamRepository.searchByKeyword(any(), any(Pageable.class))).willReturn(new PageImpl<>(List.of()));
            given(organizationRepository.searchByKeyword(any(), any(Pageable.class))).willReturn(new PageImpl<>(List.of()));
            given(userRepository.searchByKeyword(any(), any(Pageable.class))).willReturn(List.of());

            // when
            globalSearchService.search("検索", USER_ID);

            // then
            verify(scheduleRepository).searchByKeyword(any(), any(Pageable.class));
            verify(eventRepository).searchByKeyword(any(), any(Pageable.class));
            verify(facilityBookingRepository).searchByKeyword(any(), any(Pageable.class));
            verify(shiftScheduleRepository).searchByKeyword(any(), any(Pageable.class));
            verify(safetyCheckRepository).searchByKeyword(any(), any(Pageable.class));
            verify(queueTicketRepository).searchByKeyword(any(), any(Pageable.class));
            verify(teamRepository).searchByKeyword(any(), any(Pageable.class));
            verify(organizationRepository).searchByKeyword(any(), any(Pageable.class));
            verify(userRepository).searchByKeyword(any(), any(Pageable.class));
        }
    }
}
