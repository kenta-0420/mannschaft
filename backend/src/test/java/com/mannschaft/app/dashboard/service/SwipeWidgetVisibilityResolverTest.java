package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.dashboard.MinRole;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.dashboard.SwipeWidgetKey;
import com.mannschaft.app.dashboard.ViewerRole;
import com.mannschaft.app.dashboard.entity.DashboardWidgetRoleVisibilityEntity;
import com.mannschaft.app.dashboard.repository.DashboardWidgetRoleVisibilityRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * F22.1 第二波: {@link SwipeWidgetVisibilityResolver} の単体テスト。
 *
 * <p>SWIPE_* キーの既定 MEMBER・スコープ別フィルタ・DB 上書き反映・可視性判定を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SwipeWidgetVisibilityResolver 単体テスト")
class SwipeWidgetVisibilityResolverTest {

    @Mock
    private DashboardWidgetRoleVisibilityRepository repository;

    @InjectMocks
    private SwipeWidgetVisibilityResolver resolver;

    @Nested
    @DisplayName("resolve")
    class Resolve {

        @Test
        @DisplayName("TEAM スコープ: SWIPE_TEAM_* 8 件が既定 MEMBER で返る")
        void team_8件MEMBER() {
            given(repository.findByScopeTypeAndScopeId(ScopeType.TEAM, 10L)).willReturn(List.of());

            Map<SwipeWidgetKey, MinRole> result = resolver.resolve("TEAM", 10L);

            assertThat(result).hasSize(8);
            assertThat(result.keySet()).allSatisfy(k ->
                    assertThat(k.getScopeType()).isEqualTo(ScopeType.TEAM));
            assertThat(result.values()).allMatch(v -> v == MinRole.MEMBER);
        }

        @Test
        @DisplayName("ORGANIZATION スコープ: SWIPE_ORG_* 8 件が返る")
        void org_8件() {
            given(repository.findByScopeTypeAndScopeId(ScopeType.ORGANIZATION, 20L)).willReturn(List.of());

            Map<SwipeWidgetKey, MinRole> result = resolver.resolve("ORGANIZATION", 20L);

            assertThat(result).hasSize(8);
            assertThat(result.keySet()).allSatisfy(k ->
                    assertThat(k.getScopeType()).isEqualTo(ScopeType.ORGANIZATION));
        }

        @Test
        @DisplayName("DB 上書きがあれば反映される（F02.2.1 既存キーは無視）")
        void db上書き反映() {
            DashboardWidgetRoleVisibilityEntity swipeOverride = DashboardWidgetRoleVisibilityEntity.builder()
                    .scopeType(ScopeType.TEAM).scopeId(10L)
                    .widgetKey(SwipeWidgetKey.SWIPE_TEAM_BLOG.name())
                    .minRole(MinRole.SUPPORTER).build();
            DashboardWidgetRoleVisibilityEntity legacyOverride = DashboardWidgetRoleVisibilityEntity.builder()
                    .scopeType(ScopeType.TEAM).scopeId(10L)
                    .widgetKey("TEAM_NOTICES").minRole(MinRole.PUBLIC).build();
            given(repository.findByScopeTypeAndScopeId(ScopeType.TEAM, 10L))
                    .willReturn(List.of(swipeOverride, legacyOverride));

            Map<SwipeWidgetKey, MinRole> result = resolver.resolve("TEAM", 10L);

            assertThat(result.get(SwipeWidgetKey.SWIPE_TEAM_BLOG)).isEqualTo(MinRole.SUPPORTER);
            // 既定のまま
            assertThat(result.get(SwipeWidgetKey.SWIPE_TEAM_CHAT)).isEqualTo(MinRole.MEMBER);
            // F02.2.1 既存キーは混入しない
            assertThat(result).hasSize(8);
        }
    }

    @Nested
    @DisplayName("filterIfVisible")
    class FilterIfVisible {

        private final Map<SwipeWidgetKey, MinRole> map =
                Map.of(SwipeWidgetKey.SWIPE_TEAM_BLOG, MinRole.MEMBER);

        @Test
        @DisplayName("MEMBER 閲覧者 → MEMBER ウィジェットは可視（data 返る）")
        void member_可視() {
            String data = "x";
            assertThat(resolver.filterIfVisible(ViewerRole.MEMBER, map, SwipeWidgetKey.SWIPE_TEAM_BLOG, data))
                    .isEqualTo(data);
        }

        @Test
        @DisplayName("SUPPORTER 閲覧者 → MEMBER ウィジェットは不可視（null）")
        void supporter_不可視() {
            assertThat(resolver.filterIfVisible(ViewerRole.SUPPORTER, map, SwipeWidgetKey.SWIPE_TEAM_BLOG, "x"))
                    .isNull();
        }

        @Test
        @DisplayName("ADMIN 閲覧者 → 常に可視（バイパス）")
        void admin_バイパス() {
            assertThat(resolver.filterIfVisible(ViewerRole.ADMIN, map, SwipeWidgetKey.SWIPE_TEAM_BLOG, "x"))
                    .isEqualTo("x");
        }

        @Test
        @DisplayName("data が null なら null を返す")
        void null_data() {
            String nullData = null;
            assertThat(resolver.filterIfVisible(ViewerRole.MEMBER, map, SwipeWidgetKey.SWIPE_TEAM_BLOG, nullData))
                    .isNull();
        }
    }
}
