package com.mannschaft.app.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.dto.AdminBusinessAlertSummaryResponse;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.team.service.TeamService;
import com.mannschaft.app.template.service.ModuleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link AdminBusinessAlertService} の単体テスト。
 * キャッシュ制御・集計ロジック・権限フィルタリングを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminBusinessAlertService 単体テスト")
class AdminBusinessAlertServiceTest {

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ChatChannelRepository chatChannelRepository;

    @Mock
    private ChatChannelMemberRepository chatChannelMemberRepository;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private ModuleService moduleService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private Clock wallClock;

    @Mock
    private com.mannschaft.app.role.service.PermissionScopeQueryService permissionScopeQueryService;

    @Mock
    private com.mannschaft.app.chat.service.ChatMemberBatchQueryService chatMemberBatchQueryService;

    @Mock
    private TeamService teamService;

    @InjectMocks
    private AdminBusinessAlertService adminBusinessAlertService;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final String CACHE_KEY = "admin_alert_summary:" + USER_ID;
    private static final Instant CURRENT_INSTANT = Instant.parse("2026-04-09T15:30:00Z");

    @BeforeEach
    void setUpWallClock() {
        lenient().when(wallClock.instant()).thenReturn(CURRENT_INSTANT);
        lenient().when(wallClock.getZone()).thenReturn(UserZoneLocalDateTimeParser.SERVER_ZONE);
    }

    // ========================================
    // getSummary - キャッシュ制御
    // ========================================

    @Nested
    @DisplayName("getSummary - キャッシュ制御")
    class GetSummaryCache {

        @Test
        @DisplayName("キャッシュミス時はDBを呼び出してサマリーを構築する")
        void getSummary_キャッシュミス_DBを呼び出す() throws Exception {
            // Given: キャッシュ未ヒット
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get(CACHE_KEY)).willReturn(null);
            given(userRoleRepository.findAdminAndDeputyAdminTeamIds(USER_ID)).willReturn(List.of());

            // When
            AdminBusinessAlertSummaryResponse result = adminBusinessAlertService.getSummary(USER_ID);

            // Then: DB を呼び出している
            verify(userRoleRepository).findAdminAndDeputyAdminTeamIds(USER_ID);
            assertThat(result).isNotNull();
            assertThat(result.getData().getTeams()).isEmpty();
            assertThat(result.getData().getTotalPending()).isZero();
        }

        @Test
        @DisplayName("キャッシュヒット時はDBを呼び出さない")
        void getSummary_キャッシュヒット_DBを呼び出さない() throws Exception {
            // Given: キャッシュヒット（デシリアライズ成功）
            String cachedJson = "{\"data\":{\"teams\":[],\"totalPending\":0}}";
            AdminBusinessAlertSummaryResponse cachedResponse = AdminBusinessAlertSummaryResponse.builder()
                    .data(AdminBusinessAlertSummaryResponse.Data.builder()
                            .teams(List.of())
                            .totalPending(0)
                            .build())
                    .build();

            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get(CACHE_KEY)).willReturn(cachedJson);
            given(objectMapper.readValue(cachedJson, AdminBusinessAlertSummaryResponse.class))
                    .willReturn(cachedResponse);

            // When
            AdminBusinessAlertSummaryResponse result = adminBusinessAlertService.getSummary(USER_ID);

            // Then: DB を呼び出していない
            verify(userRoleRepository, never()).findAdminAndDeputyAdminTeamIds(anyLong());
            assertThat(result).isNotNull();
            assertThat(result.getData().getTotalPending()).isZero();
        }
    }

    // ========================================
    // invalidateCache
    // ========================================

    @Nested
    @DisplayName("invalidateCache")
    class InvalidateCache {

        @Test
        @DisplayName("invalidateCache は Valkey から該当キーを削除する")
        void invalidateCache_指定ユーザーのキーを削除する() {
            // Given（削除は戻り値なしのため given 不要）

            // When
            adminBusinessAlertService.invalidateCache(USER_ID);

            // Then
            verify(redisTemplate).delete(CACHE_KEY);
        }
    }

    // ========================================
    // getSummary - 権限フィルタリング・集計
    // ========================================

    @Nested
    @DisplayName("getSummary - 集計ロジック")
    class GetSummaryCounting {

        @Test
        @DisplayName("予約モジュール無効の場合は予約カウントが0になる")
        void getSummary_予約モジュール無効_予約カウントゼロ() throws Exception {
            // Given
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get(CACHE_KEY)).willReturn(null);
            given(userRoleRepository.findAdminAndDeputyAdminTeamIds(USER_ID)).willReturn(List.of(TEAM_ID));

            // TeamEntity の id は BaseEntity の @GeneratedValue フィールドのため ReflectionTestUtils で設定する
            TeamEntity team = TeamEntity.builder()
                    .name("テストチーム")
                    .build();
            ReflectionTestUtils.setField(team, "id", TEAM_ID);
            given(teamRepository.findAllById(List.of(TEAM_ID))).willReturn(List.of(team));

            // 予約モジュール無効
            given(moduleService.isModuleEnabledForTeams("reservation", List.of(TEAM_ID)))
                    .willReturn(java.util.Map.of(TEAM_ID, false));

            // 問い合わせチャンネルなし
            given(chatChannelRepository.findByTeamIdInAndIsInquiryChannelTrue(List.of(TEAM_ID)))
                    .willReturn(List.of());

            // キャッシュ書き込みのスタブ（set は void）
            given(objectMapper.writeValueAsString(any())).willReturn("{}");

            // When
            AdminBusinessAlertSummaryResponse result = adminBusinessAlertService.getSummary(USER_ID);

            // Then: 予約カウントは0
            assertThat(result.getData().getTeams()).hasSize(1);
            AdminBusinessAlertSummaryResponse.TeamAlert teamAlert = result.getData().getTeams().get(0);
            assertThat(teamAlert.isReservationModuleEnabled()).isFalse();
            assertThat(teamAlert.getAlerts().getNewReservations()).isZero();
            assertThat(teamAlert.getAlerts().getPendingApproval()).isZero();
        }

        @Test
        @DisplayName("ADMIN権限ユーザーには予約・問い合わせ両方が含まれる")
        void getSummary_ADMIN権限_予約と問い合わせを集計する() throws Exception {
            // Given
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get(CACHE_KEY)).willReturn(null);
            given(userRoleRepository.findAdminAndDeputyAdminTeamIds(USER_ID)).willReturn(List.of(TEAM_ID));

            // TeamEntity の id は BaseEntity の @GeneratedValue フィールドのため ReflectionTestUtils で設定する
            TeamEntity team = TeamEntity.builder()
                    .name("テストチーム")
                    .build();
            ReflectionTestUtils.setField(team, "id", TEAM_ID);
            given(teamRepository.findAllById(List.of(TEAM_ID))).willReturn(List.of(team));

            // 予約モジュール有効
            given(moduleService.isModuleEnabledForTeams("reservation", List.of(TEAM_ID)))
                    .willReturn(java.util.Map.of(TEAM_ID, true));

            // ADMIN 権限（予約カウント対象）
            // CMP-261103-1225: チームごとの判定ではなくチーム群まとめて判定する
            given(permissionScopeQueryService.findAdminTeamIds(USER_ID, List.of(TEAM_ID)))
                    .willReturn(java.util.Set.of(TEAM_ID));

            // 本日の確定予約数: 2
            java.util.ArrayList<Object[]> todayConfirmed = new java.util.ArrayList<>();
            todayConfirmed.add(new Object[]{TEAM_ID, 2});
            given(reservationRepository.countTodayConfirmedByTeamIds(
                    eq(List.of(TEAM_ID)), eq(LocalDateTime.of(2026, 4, 9, 15, 0))))
                    .willReturn(todayConfirmed);

            // 承認待ち: 1
            java.util.ArrayList<Object[]> pendingApproval = new java.util.ArrayList<>();
            pendingApproval.add(new Object[]{TEAM_ID, 1});
            given(reservationRepository.countPendingByTeamIds(List.of(TEAM_ID)))
                    .willReturn(pendingApproval);

            // 問い合わせチャンネルなし
            given(chatChannelRepository.findByTeamIdInAndIsInquiryChannelTrue(List.of(TEAM_ID)))
                    .willReturn(List.of());

            // キャッシュ書き込みのスタブ（set は void）
            given(objectMapper.writeValueAsString(any())).willReturn("{}");

            // When
            AdminBusinessAlertSummaryResponse result = adminBusinessAlertService.getSummary(USER_ID);

            // Then
            assertThat(result.getData().getTeams()).hasSize(1);
            AdminBusinessAlertSummaryResponse.TeamAlert teamAlert = result.getData().getTeams().get(0);
            assertThat(teamAlert.isReservationModuleEnabled()).isTrue();
            assertThat(teamAlert.getAlerts().getNewReservations()).isEqualTo(2);
            assertThat(teamAlert.getAlerts().getPendingApproval()).isEqualTo(1);
            assertThat(result.getData().getTotalPending()).isEqualTo(3);
        }
    }

    // ========================================
    // CMP-261030-0648: links の slug 化
    // ========================================

    @Nested
    @DisplayName("getSummary - links の slug 化（CMP-261030-0648）")
    class GetSummaryLinksSlug {

        private static final Long TEAM_B = 20L;
        private static final Long CHANNEL_A = 501L;
        private static final String NUMERIC_TEAM_URL = ".*/teams/\\d+(/|\\?|$).*";

        private TeamEntity team(Long id, String name) {
            TeamEntity team = TeamEntity.builder().name(name).build();
            ReflectionTestUtils.setField(team, "id", id);
            return team;
        }

        private ChatChannelEntity inquiryChannel(Long id, Long teamId) {
            ChatChannelEntity channel = ChatChannelEntity.builder().teamId(teamId).build();
            ReflectionTestUtils.setField(channel, "id", id);
            return channel;
        }

        /** チーム A（問い合わせチャンネルあり）とチーム B（チャンネルなし）を管理しているユーザーの集計を用意する。 */
        private void stubTwoTeams() throws Exception {
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get(CACHE_KEY)).willReturn(null);
            List<Long> teamIds = List.of(TEAM_ID, TEAM_B);
            given(userRoleRepository.findAdminAndDeputyAdminTeamIds(USER_ID)).willReturn(teamIds);
            given(teamRepository.findAllById(teamIds)).willReturn(List.of(team(TEAM_ID, "A"), team(TEAM_B, "B")));
            given(moduleService.isModuleEnabledForTeams("reservation", teamIds))
                    .willReturn(java.util.Map.of(TEAM_ID, false, TEAM_B, false));
            given(chatChannelRepository.findByTeamIdInAndIsInquiryChannelTrue(teamIds))
                    .willReturn(List.of(inquiryChannel(CHANNEL_A, TEAM_ID)));
            given(chatMemberBatchQueryService.findUnreadCountByChannelForMember(USER_ID, List.of(CHANNEL_A)))
                    .willReturn(java.util.Map.of(CHANNEL_A, 0));
            lenient().when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        }

        private AdminBusinessAlertSummaryResponse.Links linksOf(AdminBusinessAlertSummaryResponse r, Long teamId) {
            return r.getData().getTeams().stream()
                    .filter(t -> t.getTeamId().equals(teamId))
                    .findFirst().orElseThrow()
                    .getLinks();
        }

        @Test
        @DisplayName("AC-3/AC-4/AC-17/AC-20a: 各チームのリンクは自分の slug。チャンネルが無いチームの inquiryChannelUrl は null。getSlugsByIds は1回だけ")
        void ac3_links_use_each_team_slug() throws Exception {
            stubTwoTeams();
            given(teamService.getSlugsByIds(List.of(TEAM_ID, TEAM_B)))
                    .willReturn(java.util.Map.of(TEAM_ID, "lions-fc", TEAM_B, "tigers"));

            AdminBusinessAlertSummaryResponse result = adminBusinessAlertService.getSummary(USER_ID);

            AdminBusinessAlertSummaryResponse.Links a = linksOf(result, TEAM_ID);
            AdminBusinessAlertSummaryResponse.Links b = linksOf(result, TEAM_B);
            assertThat(a.getReservationsUrl()).isEqualTo("/teams/lions-fc/reservations");
            assertThat(a.getInquiryChannelUrl()).isEqualTo("/teams/lions-fc/chat?channel=" + CHANNEL_A);
            assertThat(b.getReservationsUrl()).isEqualTo("/teams/tigers/reservations");
            assertThat(b.getInquiryChannelUrl()).isNull();
            assertThat(List.of(a.getReservationsUrl(), a.getInquiryChannelUrl(), b.getReservationsUrl()))
                    .allSatisfy(u -> assertThat(u).doesNotMatch(NUMERIC_TEAM_URL));
            verify(teamService, times(1)).getSlugsByIds(anyCollection());
        }

        @Test
        @DisplayName("AC-12: 30文字・ハイフン入りの slug がそのまま URL に入る")
        void ac12_long_hyphenated_slug_is_used_verbatim() throws Exception {
            stubTwoTeams();
            given(teamService.getSlugsByIds(List.of(TEAM_ID, TEAM_B)))
                    .willReturn(java.util.Map.of(TEAM_ID, "abcdefghij-klmnopqrs-tuvwxyz01", TEAM_B, "tigers"));

            AdminBusinessAlertSummaryResponse result = adminBusinessAlertService.getSummary(USER_ID);

            assertThat(linksOf(result, TEAM_ID).getReservationsUrl())
                    .isEqualTo("/teams/abcdefghij-klmnopqrs-tuvwxyz01/reservations");
        }

        @Test
        @DisplayName("AC-13: getSlugsByIds の結果に欠けたチームだけ /notifications、ほかは slug")
        void ac13_missing_slug_falls_back_only_for_that_team() throws Exception {
            stubTwoTeams();
            given(teamService.getSlugsByIds(List.of(TEAM_ID, TEAM_B)))
                    .willReturn(java.util.Map.of(TEAM_B, "tigers"));

            AdminBusinessAlertSummaryResponse result = adminBusinessAlertService.getSummary(USER_ID);

            AdminBusinessAlertSummaryResponse.Links a = linksOf(result, TEAM_ID);
            assertThat(a.getReservationsUrl()).isEqualTo("/notifications");
            assertThat(a.getInquiryChannelUrl()).isEqualTo("/notifications");
            assertThat(linksOf(result, TEAM_B).getReservationsUrl()).isEqualTo("/teams/tigers/reservations");
        }

        @Test
        @DisplayName("AC-16: getSlugsByIds が例外なら要約は失敗し、その結果は Valkey にキャッシュされない")
        void ac16_slug_exception_fails_summary_without_caching() throws Exception {
            stubTwoTeams();
            given(teamService.getSlugsByIds(anyCollection())).willThrow(new IllegalStateException("db down"));

            assertThatThrownBy(() -> adminBusinessAlertService.getSummary(USER_ID))
                    .isInstanceOf(IllegalStateException.class);

            verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any());
        }

        @Test
        @DisplayName("AC-11/AC-20c: 管理チームが0件なら getSlugsByIds を呼ばずに空の応答")
        void ac11_no_admin_teams_skips_slug_resolution() {
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get(CACHE_KEY)).willReturn(null);
            given(userRoleRepository.findAdminAndDeputyAdminTeamIds(USER_ID)).willReturn(List.of());

            AdminBusinessAlertSummaryResponse result = adminBusinessAlertService.getSummary(USER_ID);

            assertThat(result.getData().getTeams()).isEmpty();
            verifyNoInteractions(teamService);
        }

        @Test
        @DisplayName("AC-20b: キャッシュに当たれば getSlugsByIds は0回")
        void ac20b_cache_hit_skips_slug_resolution() throws Exception {
            String cachedJson = "{\"data\":{\"teams\":[],\"totalPending\":0}}";
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get(CACHE_KEY)).willReturn(cachedJson);
            given(objectMapper.readValue(cachedJson, AdminBusinessAlertSummaryResponse.class))
                    .willReturn(AdminBusinessAlertSummaryResponse.builder()
                            .data(AdminBusinessAlertSummaryResponse.Data.builder()
                                    .teams(List.of()).totalPending(0).build())
                            .build());

            adminBusinessAlertService.getSummary(USER_ID);

            verifyNoInteractions(teamService);
        }

        @Test
        @DisplayName("AC-18: 応答に載るのは ADMIN/DEPUTY_ADMIN のチームだけで、slug もそのチーム群だけを引く")
        void ac18_only_admin_or_deputy_teams_are_listed() throws Exception {
            stubTwoTeams();
            given(teamService.getSlugsByIds(List.of(TEAM_ID, TEAM_B)))
                    .willReturn(java.util.Map.of(TEAM_ID, "lions-fc", TEAM_B, "tigers"));

            AdminBusinessAlertSummaryResponse result = adminBusinessAlertService.getSummary(USER_ID);

            assertThat(result.getData().getTeams())
                    .extracting(AdminBusinessAlertSummaryResponse.TeamAlert::getTeamId)
                    .containsExactly(TEAM_ID, TEAM_B);
            verify(userRoleRepository).findAdminAndDeputyAdminTeamIds(USER_ID);
            verify(teamService).getSlugsByIds(List.of(TEAM_ID, TEAM_B));
        }
    }
}
