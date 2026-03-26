package com.mannschaft.app.dashboard;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.dashboard.dto.ActivityFeedResponse;
import com.mannschaft.app.dashboard.entity.ActivityFeedEntity;
import com.mannschaft.app.dashboard.repository.ActivityFeedRepository;
import com.mannschaft.app.dashboard.service.ActivityFeedService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ActivityFeedService} の単体テスト。
 * アクティビティフィードのクエリ処理を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityFeedService 単体テスト")
class ActivityFeedServiceTest {

    @Mock
    private ActivityFeedRepository activityFeedRepository;

    @Mock
    private DashboardMapper dashboardMapper;

    @Mock
    private NameResolverService nameResolverService;

    @InjectMocks
    private ActivityFeedService activityFeedService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long USER_ID = 1L;
    private static final Long SCOPE_ID_TEAM = 10L;
    private static final Long SCOPE_ID_ORG = 20L;
    private static final Long ACTOR_ID = 99L;
    private static final Long CURSOR_ID = 50L;

    private ActivityFeedEntity createActivityFeedEntity(Long actorId, ScopeType scopeType, Long scopeId) {
        return ActivityFeedEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .actorId(actorId)
                .activityType(ActivityType.POST_CREATED)
                .targetType(TargetType.TIMELINE_POST)
                .targetId(100L)
                .summary("テスト投稿を作成しました")
                .createdAt(LocalDateTime.now())
                .build();
    }

    private ActivityFeedResponse createActivityFeedResponse(Long id, String scopeName) {
        return new ActivityFeedResponse(
                id, "POST_CREATED",
                new ActivityFeedResponse.ActorSummary(ACTOR_ID, "テストユーザー", null),
                "TEAM", SCOPE_ID_TEAM, scopeName,
                "TIMELINE_POST", 100L, "テスト投稿を作成しました",
                LocalDateTime.now()
        );
    }

    // ========================================
    // getActivityFeed
    // ========================================

    @Nested
    @DisplayName("getActivityFeed")
    class GetActivityFeed {

        @Test
        @DisplayName("正常系: カーソルなしで最新アクティビティが取得される")
        void getActivityFeed_カーソルなし_最新取得() {
            // Given
            List<Long> scopeIds = List.of(SCOPE_ID_TEAM);
            ActivityFeedEntity entity = createActivityFeedEntity(ACTOR_ID, ScopeType.TEAM, SCOPE_ID_TEAM);
            List<ActivityFeedEntity> entities = List.of(entity);

            given(activityFeedRepository.findByScopesAndExcludeActor(
                    eq(List.of(ScopeType.TEAM, ScopeType.ORGANIZATION)),
                    eq(scopeIds), eq(USER_ID), any(PageRequest.class)))
                    .willReturn(entities);

            given(nameResolverService.resolveUserDisplayNames(Set.of(ACTOR_ID)))
                    .willReturn(Map.of(ACTOR_ID, "テストユーザー"));
            given(nameResolverService.resolveTeamNames(Set.of(SCOPE_ID_TEAM)))
                    .willReturn(Map.of(SCOPE_ID_TEAM, "テストチーム"));
            given(nameResolverService.resolveOrganizationNames(Set.of()))
                    .willReturn(Map.of());

            ActivityFeedResponse response = createActivityFeedResponse(1L, "テストチーム");
            given(dashboardMapper.toActivityFeedResponse(eq(entity), any(ActivityFeedResponse.ActorSummary.class), eq("テストチーム")))
                    .willReturn(response);

            // When
            List<ActivityFeedResponse> result = activityFeedService.getActivityFeed(USER_ID, null, 10, scopeIds);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getType()).isEqualTo("POST_CREATED");
            assertThat(result.get(0).getScopeName()).isEqualTo("テストチーム");
            verify(activityFeedRepository).findByScopesAndExcludeActor(
                    any(), eq(scopeIds), eq(USER_ID), any(PageRequest.class));
        }

        @Test
        @DisplayName("正常系: カーソル指定で古いアクティビティが取得される")
        void getActivityFeed_カーソルあり_古いデータ取得() {
            // Given
            List<Long> scopeIds = List.of(SCOPE_ID_TEAM);
            ActivityFeedEntity entity = createActivityFeedEntity(ACTOR_ID, ScopeType.TEAM, SCOPE_ID_TEAM);
            List<ActivityFeedEntity> entities = List.of(entity);

            given(activityFeedRepository.findByScopeAndExcludeActorWithCursor(
                    eq(List.of(ScopeType.TEAM, ScopeType.ORGANIZATION)),
                    eq(scopeIds), eq(USER_ID), eq(CURSOR_ID), any(PageRequest.class)))
                    .willReturn(entities);

            given(nameResolverService.resolveUserDisplayNames(any())).willReturn(Map.of(ACTOR_ID, "テストユーザー"));
            given(nameResolverService.resolveTeamNames(any())).willReturn(Map.of(SCOPE_ID_TEAM, "テストチーム"));
            given(nameResolverService.resolveOrganizationNames(any())).willReturn(Map.of());

            ActivityFeedResponse response = createActivityFeedResponse(1L, "テストチーム");
            given(dashboardMapper.toActivityFeedResponse(any(), any(), any())).willReturn(response);

            // When
            List<ActivityFeedResponse> result = activityFeedService.getActivityFeed(USER_ID, CURSOR_ID, 10, scopeIds);

            // Then
            assertThat(result).hasSize(1);
            verify(activityFeedRepository).findByScopeAndExcludeActorWithCursor(
                    any(), eq(scopeIds), eq(USER_ID), eq(CURSOR_ID), any(PageRequest.class));
        }

        @Test
        @DisplayName("正常系: limitがnullの場合デフォルト10件で取得される")
        void getActivityFeed_limitがnull_デフォルト10件() {
            // Given
            List<Long> scopeIds = List.of(SCOPE_ID_TEAM);
            given(activityFeedRepository.findByScopesAndExcludeActor(
                    any(), eq(scopeIds), eq(USER_ID), eq(PageRequest.of(0, 10))))
                    .willReturn(List.of());
            given(nameResolverService.resolveUserDisplayNames(any())).willReturn(Map.of());
            given(nameResolverService.resolveTeamNames(any())).willReturn(Map.of());
            given(nameResolverService.resolveOrganizationNames(any())).willReturn(Map.of());

            // When
            List<ActivityFeedResponse> result = activityFeedService.getActivityFeed(USER_ID, null, null, scopeIds);

            // Then
            assertThat(result).isEmpty();
            verify(activityFeedRepository).findByScopesAndExcludeActor(
                    any(), eq(scopeIds), eq(USER_ID), eq(PageRequest.of(0, 10)));
        }

        @Test
        @DisplayName("正常系: limitが上限50を超える場合50件に制限される")
        void getActivityFeed_limit超過_50件に制限() {
            // Given
            List<Long> scopeIds = List.of(SCOPE_ID_TEAM);
            given(activityFeedRepository.findByScopesAndExcludeActor(
                    any(), eq(scopeIds), eq(USER_ID), eq(PageRequest.of(0, 50))))
                    .willReturn(List.of());
            given(nameResolverService.resolveUserDisplayNames(any())).willReturn(Map.of());
            given(nameResolverService.resolveTeamNames(any())).willReturn(Map.of());
            given(nameResolverService.resolveOrganizationNames(any())).willReturn(Map.of());

            // When
            List<ActivityFeedResponse> result = activityFeedService.getActivityFeed(USER_ID, null, 100, scopeIds);

            // Then
            assertThat(result).isEmpty();
            verify(activityFeedRepository).findByScopesAndExcludeActor(
                    any(), eq(scopeIds), eq(USER_ID), eq(PageRequest.of(0, 50)));
        }

        @Test
        @DisplayName("正常系: アクター名が解決できない場合は不明なユーザーが使われる")
        void getActivityFeed_アクター不明_デフォルト名使用() {
            // Given
            List<Long> scopeIds = List.of(SCOPE_ID_TEAM);
            ActivityFeedEntity entity = createActivityFeedEntity(ACTOR_ID, ScopeType.TEAM, SCOPE_ID_TEAM);
            List<ActivityFeedEntity> entities = List.of(entity);

            given(activityFeedRepository.findByScopesAndExcludeActor(any(), eq(scopeIds), eq(USER_ID), any(PageRequest.class)))
                    .willReturn(entities);

            // アクター名が解決できない（空マップ）
            given(nameResolverService.resolveUserDisplayNames(any())).willReturn(Map.of());
            given(nameResolverService.resolveTeamNames(any())).willReturn(Map.of(SCOPE_ID_TEAM, "テストチーム"));
            given(nameResolverService.resolveOrganizationNames(any())).willReturn(Map.of());

            ActivityFeedResponse response = createActivityFeedResponse(1L, "テストチーム");
            given(dashboardMapper.toActivityFeedResponse(
                    eq(entity),
                    argThat(actor -> "不明なユーザー".equals(actor.getDisplayName())),
                    eq("テストチーム")))
                    .willReturn(response);

            // When
            List<ActivityFeedResponse> result = activityFeedService.getActivityFeed(USER_ID, null, 5, scopeIds);

            // Then
            assertThat(result).hasSize(1);
            verify(dashboardMapper).toActivityFeedResponse(
                    eq(entity),
                    argThat(actor -> "不明なユーザー".equals(actor.getDisplayName())),
                    eq("テストチーム"));
        }

        @Test
        @DisplayName("正常系: 組織スコープのアクティビティが含まれる場合に組織名が解決される")
        void getActivityFeed_組織スコープ含む_組織名解決() {
            // Given
            List<Long> scopeIds = List.of(SCOPE_ID_TEAM, SCOPE_ID_ORG);
            ActivityFeedEntity teamEntity = createActivityFeedEntity(ACTOR_ID, ScopeType.TEAM, SCOPE_ID_TEAM);
            ActivityFeedEntity orgEntity = createActivityFeedEntity(ACTOR_ID, ScopeType.ORGANIZATION, SCOPE_ID_ORG);
            List<ActivityFeedEntity> entities = List.of(teamEntity, orgEntity);

            given(activityFeedRepository.findByScopesAndExcludeActor(any(), eq(scopeIds), eq(USER_ID), any(PageRequest.class)))
                    .willReturn(entities);

            given(nameResolverService.resolveUserDisplayNames(any())).willReturn(Map.of(ACTOR_ID, "テストユーザー"));
            given(nameResolverService.resolveTeamNames(Set.of(SCOPE_ID_TEAM))).willReturn(Map.of(SCOPE_ID_TEAM, "テストチーム"));
            given(nameResolverService.resolveOrganizationNames(Set.of(SCOPE_ID_ORG))).willReturn(Map.of(SCOPE_ID_ORG, "テスト組織"));

            given(dashboardMapper.toActivityFeedResponse(any(), any(), anyString()))
                    .willAnswer(inv -> createActivityFeedResponse(1L, inv.getArgument(2)));

            // When
            List<ActivityFeedResponse> result = activityFeedService.getActivityFeed(USER_ID, null, 10, scopeIds);

            // Then
            assertThat(result).hasSize(2);
            verify(nameResolverService).resolveTeamNames(Set.of(SCOPE_ID_TEAM));
            verify(nameResolverService).resolveOrganizationNames(Set.of(SCOPE_ID_ORG));
        }

        @Test
        @DisplayName("正常系: 結果が空の場合は空リストが返却される")
        void getActivityFeed_結果なし_空リスト() {
            // Given
            List<Long> scopeIds = List.of(SCOPE_ID_TEAM);
            given(activityFeedRepository.findByScopesAndExcludeActor(any(), eq(scopeIds), eq(USER_ID), any(PageRequest.class)))
                    .willReturn(List.of());
            given(nameResolverService.resolveUserDisplayNames(any())).willReturn(Map.of());
            given(nameResolverService.resolveTeamNames(any())).willReturn(Map.of());
            given(nameResolverService.resolveOrganizationNames(any())).willReturn(Map.of());

            // When
            List<ActivityFeedResponse> result = activityFeedService.getActivityFeed(USER_ID, null, 10, scopeIds);

            // Then
            assertThat(result).isEmpty();
        }
    }
}
