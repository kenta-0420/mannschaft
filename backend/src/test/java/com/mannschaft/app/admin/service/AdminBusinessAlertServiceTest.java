package com.mannschaft.app.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.dto.AdminBusinessAlertSummaryResponse;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.template.service.ModuleService;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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

    @InjectMocks
    private AdminBusinessAlertService adminBusinessAlertService;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final String CACHE_KEY = "admin_alert_summary:" + USER_ID;

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
            given(moduleService.isModuleEnabledForTeam("reservation", TEAM_ID)).willReturn(false);

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
            given(moduleService.isModuleEnabledForTeam("reservation", TEAM_ID)).willReturn(true);

            // ADMIN 権限（予約カウント対象）
            given(userRoleRepository.findAdminUserIdsByTeamId(TEAM_ID)).willReturn(List.of(USER_ID));

            // 本日の確定予約数: 2
            java.util.ArrayList<Object[]> todayConfirmed = new java.util.ArrayList<>();
            todayConfirmed.add(new Object[]{TEAM_ID, 2});
            given(reservationRepository.countTodayConfirmedByTeamIds(eq(List.of(TEAM_ID)), any()))
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
}
