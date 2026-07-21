package com.mannschaft.app.search.service;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.facility.repository.FacilityBookingRepository;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.queue.repository.QueueTicketRepository;
import com.mannschaft.app.safetycheck.repository.SafetyCheckRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.search.dto.SearchResultResponse;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link GlobalSearchService} の単体テスト。
 * 9種別横断検索と、閲覧者依存の可視性フィルタの結線を検証する。
 *
 * <p>認可根治 Wave6: 本サービスは各種別のリポジトリクエリに
 * 「閲覧者が所属するチーム／組織 ID 集合」と「閲覧者自身の ID」を渡し、クエリ段階で可視範囲へ絞る。
 * 本単体テストは<strong>その引数が実際にリポジトリまで届いていること</strong>（結線）を固定する。
 * SQL 述語そのものの正しさ（越境データが本当に出ないこと・自スコープのデータが出ること）は
 * {@code GlobalSearchVisibilityContractIT} が実 DB で検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
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

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private MembershipService membershipService;

    @InjectMocks
    private GlobalSearchService globalSearchService;

    @Captor
    private ArgumentCaptor<Collection<Long>> teamIdsCaptor;

    @Captor
    private ArgumentCaptor<Collection<Long>> orgIdsCaptor;

    private static final Long USER_ID = 100L;
    private static final Long TEAM_ID = 10L;
    private static final Long ORG_ID = 20L;
    private static final Long CO_MEMBER_ID = 101L;

    /** 所属が無い利用者に対して JPQL の {@code IN ()} を避けるためのダミー値（どのレコードにも一致しない）。 */
    private static final Long NO_MATCH_ID = -1L;

    @BeforeEach
    void setUp() {
        // 既定: TEAM_ID / ORG_ID に所属し、CO_MEMBER_ID と所属を共有する利用者
        given(accessControlService.findAffiliatedScopeIds(USER_ID, "TEAM")).willReturn(Set.of(TEAM_ID));
        given(accessControlService.findAffiliatedScopeIds(USER_ID, "ORGANIZATION")).willReturn(Set.of(ORG_ID));
        given(membershipService.getActiveUserIdsInScopes(anyCollection(), anyCollection()))
                .willReturn(List.of(USER_ID, CO_MEMBER_ID));

        given(scheduleRepository.searchByKeyword(any(), anyCollection(), anyCollection(), any(), any(Pageable.class)))
                .willReturn(List.of());
        given(eventRepository.searchByKeyword(any(), anyCollection(), anyCollection(), any(), any(Pageable.class)))
                .willReturn(List.of());
        given(facilityBookingRepository.searchByKeyword(any(), anyCollection(), anyCollection(), any(), any(Pageable.class)))
                .willReturn(List.of());
        given(shiftScheduleRepository.searchByKeyword(any(), anyCollection(), any(Pageable.class)))
                .willReturn(List.of());
        given(safetyCheckRepository.searchByKeyword(any(), anyCollection(), anyCollection(), any(Pageable.class)))
                .willReturn(List.of());
        given(queueTicketRepository.searchByKeyword(any(), anyCollection(), anyCollection(), any(), any(Pageable.class)))
                .willReturn(List.of());
        given(teamRepository.searchByKeyword(any(), any(Pageable.class))).willReturn(new PageImpl<>(List.of()));
        given(organizationRepository.searchByKeyword(any(), any(Pageable.class))).willReturn(new PageImpl<>(List.of()));
        given(userRepository.searchByKeyword(any(), anyCollection(), any(Pageable.class))).willReturn(List.of());
    }

    // ========================================
    // search
    // ========================================
    @Nested
    @DisplayName("search")
    class Search {

        @Test
        @DisplayName("正常系: 9種別の横断検索結果を取得できる")
        void 横断検索結果を取得できる() {
            SearchResultResponse result = globalSearchService.search("テスト", USER_ID);

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
            globalSearchService.search("検索", USER_ID);

            verify(scheduleRepository).searchByKeyword(any(), anyCollection(), anyCollection(), any(), any(Pageable.class));
            verify(eventRepository).searchByKeyword(any(), anyCollection(), anyCollection(), any(), any(Pageable.class));
            verify(facilityBookingRepository).searchByKeyword(any(), anyCollection(), anyCollection(), any(), any(Pageable.class));
            verify(shiftScheduleRepository).searchByKeyword(any(), anyCollection(), any(Pageable.class));
            verify(safetyCheckRepository).searchByKeyword(any(), anyCollection(), anyCollection(), any(Pageable.class));
            verify(queueTicketRepository).searchByKeyword(any(), anyCollection(), anyCollection(), any(), any(Pageable.class));
            verify(teamRepository).searchByKeyword(any(), any(Pageable.class));
            verify(organizationRepository).searchByKeyword(any(), any(Pageable.class));
            verify(userRepository).searchByKeyword(any(), anyCollection(), any(Pageable.class));
        }
    }

    // ========================================
    // 可視性フィルタの結線（認可根治 Wave6）
    // ========================================
    @Nested
    @DisplayName("可視性フィルタの結線")
    class VisibilityWiring {

        @Test
        @DisplayName("閲覧者の所属スコープは AccessControlService（user_roles ∪ memberships）で解決される")
        void 所属スコープはAccessControlServiceで解決される() {
            globalSearchService.search("検索", USER_ID);

            verify(accessControlService).findAffiliatedScopeIds(USER_ID, "TEAM");
            verify(accessControlService).findAffiliatedScopeIds(USER_ID, "ORGANIZATION");
        }

        @Test
        @DisplayName("[本丸] スコープ絞り込みを持つ全種別に、解決した所属チームIDが渡る")
        void 全種別に所属チームIDが渡る() {
            globalSearchService.search("検索", USER_ID);

            verify(scheduleRepository).searchByKeyword(any(), teamIdsCaptor.capture(), anyCollection(), any(), any(Pageable.class));
            assertThat(teamIdsCaptor.getValue()).containsExactly(TEAM_ID);

            verify(eventRepository).searchByKeyword(any(), teamIdsCaptor.capture(), anyCollection(), any(), any(Pageable.class));
            assertThat(teamIdsCaptor.getValue()).containsExactly(TEAM_ID);

            verify(facilityBookingRepository).searchByKeyword(any(), teamIdsCaptor.capture(), anyCollection(), any(), any(Pageable.class));
            assertThat(teamIdsCaptor.getValue()).containsExactly(TEAM_ID);

            verify(shiftScheduleRepository).searchByKeyword(any(), teamIdsCaptor.capture(), any(Pageable.class));
            assertThat(teamIdsCaptor.getValue()).containsExactly(TEAM_ID);

            verify(safetyCheckRepository).searchByKeyword(any(), teamIdsCaptor.capture(), anyCollection(), any(Pageable.class));
            assertThat(teamIdsCaptor.getValue()).containsExactly(TEAM_ID);

            verify(queueTicketRepository).searchByKeyword(any(), teamIdsCaptor.capture(), anyCollection(), any(), any(Pageable.class));
            assertThat(teamIdsCaptor.getValue()).containsExactly(TEAM_ID);
        }

        @Test
        @DisplayName("[本丸] 組織スコープを持つ種別に、解決した所属組織IDが渡る")
        void 全種別に所属組織IDが渡る() {
            globalSearchService.search("検索", USER_ID);

            verify(scheduleRepository).searchByKeyword(any(), anyCollection(), orgIdsCaptor.capture(), any(), any(Pageable.class));
            assertThat(orgIdsCaptor.getValue()).containsExactly(ORG_ID);

            verify(eventRepository).searchByKeyword(any(), anyCollection(), orgIdsCaptor.capture(), any(), any(Pageable.class));
            assertThat(orgIdsCaptor.getValue()).containsExactly(ORG_ID);

            verify(facilityBookingRepository).searchByKeyword(any(), anyCollection(), orgIdsCaptor.capture(), any(), any(Pageable.class));
            assertThat(orgIdsCaptor.getValue()).containsExactly(ORG_ID);

            verify(safetyCheckRepository).searchByKeyword(any(), anyCollection(), orgIdsCaptor.capture(), any(Pageable.class));
            assertThat(orgIdsCaptor.getValue()).containsExactly(ORG_ID);

            verify(queueTicketRepository).searchByKeyword(any(), anyCollection(), orgIdsCaptor.capture(), any(), any(Pageable.class));
            assertThat(orgIdsCaptor.getValue()).containsExactly(ORG_ID);
        }

        @Test
        @DisplayName("[本丸] 個人スコープを持つ種別に、閲覧者自身のIDが渡る")
        void 個人スコープ種別に閲覧者IDが渡る() {
            ArgumentCaptor<Long> userIdCaptor = ArgumentCaptor.forClass(Long.class);

            globalSearchService.search("検索", USER_ID);

            verify(scheduleRepository).searchByKeyword(any(), anyCollection(), anyCollection(), userIdCaptor.capture(), any(Pageable.class));
            assertThat(userIdCaptor.getValue()).isEqualTo(USER_ID);

            verify(facilityBookingRepository).searchByKeyword(any(), anyCollection(), anyCollection(), userIdCaptor.capture(), any(Pageable.class));
            assertThat(userIdCaptor.getValue()).isEqualTo(USER_ID);

            verify(queueTicketRepository).searchByKeyword(any(), anyCollection(), anyCollection(), userIdCaptor.capture(), any(Pageable.class));
            assertThat(userIdCaptor.getValue()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("[本丸] 利用者検索には所属を共有する利用者IDのみが候補として渡る")
        void 利用者検索には同一スコープ在籍者のみ渡る() {
            ArgumentCaptor<Collection<Long>> visibleUserIdsCaptor = ArgumentCaptor.forClass(Collection.class);

            globalSearchService.search("検索", USER_ID);

            verify(membershipService).getActiveUserIdsInScopes(anyCollection(), anyCollection());
            verify(userRepository).searchByKeyword(any(), visibleUserIdsCaptor.capture(), any(Pageable.class));
            assertThat(visibleUserIdsCaptor.getValue()).containsExactlyInAnyOrder(USER_ID, CO_MEMBER_ID);
        }

        @Test
        @DisplayName("[本丸] 所属スコープが無い利用者には、どのレコードにも一致しないダミー値が渡る（fail-closed）")
        void 所属が無ければfailClosedなダミー値が渡る() {
            given(accessControlService.findAffiliatedScopeIds(USER_ID, "TEAM")).willReturn(Set.of());
            given(accessControlService.findAffiliatedScopeIds(USER_ID, "ORGANIZATION")).willReturn(Set.of());
            given(membershipService.getActiveUserIdsInScopes(anyCollection(), anyCollection())).willReturn(List.of());

            globalSearchService.search("検索", USER_ID);

            verify(scheduleRepository).searchByKeyword(any(), teamIdsCaptor.capture(), orgIdsCaptor.capture(), any(), any(Pageable.class));
            assertThat(teamIdsCaptor.getValue()).containsExactly(NO_MATCH_ID);
            assertThat(orgIdsCaptor.getValue()).containsExactly(NO_MATCH_ID);

            ArgumentCaptor<Collection<Long>> visibleUserIdsCaptor = ArgumentCaptor.forClass(Collection.class);
            verify(userRepository).searchByKeyword(any(), visibleUserIdsCaptor.capture(), any(Pageable.class));
            assertThat(visibleUserIdsCaptor.getValue()).containsExactly(NO_MATCH_ID);
        }
    }
}
