package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.dashboard.MinRole;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.dashboard.WidgetKey;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * F02.2.1: {@link WidgetVisibilityResolver} の単体テスト。
 *
 * <p>{@link DashboardWidgetRoleVisibilityRepository} をモックして、
 * デフォルト値とDB上書きの合成ロジックを検証する。</p>
 *
 * <ul>
 *   <li>DBレコードなし → デフォルト値マップが返る</li>
 *   <li>DBレコードあり → 該当キーのみデフォルトを上書き</li>
 *   <li>未知の widget_key → 警告ログ出して無視（マップに含まれない）</li>
 *   <li>ADMIN 限定の widget_key（不整合データ）→ 無視（マップに含まれない）</li>
 *   <li>PERSONAL スコープ → 空マップ</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WidgetVisibilityResolver 単体テスト")
class WidgetVisibilityResolverTest {

    @Mock
    private DashboardWidgetRoleVisibilityRepository repository;

    @InjectMocks
    private WidgetVisibilityResolver resolver;

    private static final Long TEAM_ID = 100L;
    private static final Long ORG_ID = 200L;

    // ========================================
    // DB レコードなし → デフォルト値マップ
    // ========================================

    @Nested
    @DisplayName("DB レコードなし: デフォルト値マップ")
    class DefaultsOnly {

        @Test
        @DisplayName("TEAM スコープ：デフォルト 8 件全てが返る")
        void team_デフォルト8件() {
            given(repository.findByScopeTypeAndScopeId(ScopeType.TEAM, TEAM_ID))
                    .willReturn(List.of());

            Map<WidgetKey, MinRole> result = resolver.resolve("TEAM", TEAM_ID);

            assertThat(result).hasSize(8);
            assertThat(result.get(WidgetKey.TEAM_NOTICES)).isEqualTo(MinRole.PUBLIC);
            assertThat(result.get(WidgetKey.TEAM_TODO)).isEqualTo(MinRole.MEMBER);
            assertThat(result.get(WidgetKey.TEAM_MEMBER_ATTENDANCE)).isEqualTo(MinRole.MEMBER);
            assertThat(result.get(WidgetKey.TEAM_LATEST_POSTS)).isEqualTo(MinRole.SUPPORTER);
        }

        @Test
        @DisplayName("ORGANIZATION スコープ：デフォルト 5 件全てが返る")
        void organization_デフォルト5件() {
            given(repository.findByScopeTypeAndScopeId(ScopeType.ORGANIZATION, ORG_ID))
                    .willReturn(List.of());

            Map<WidgetKey, MinRole> result = resolver.resolve("ORGANIZATION", ORG_ID);

            assertThat(result).hasSize(5);
            assertThat(result.get(WidgetKey.ORG_NOTICES)).isEqualTo(MinRole.PUBLIC);
            assertThat(result.get(WidgetKey.ORG_TODO)).isEqualTo(MinRole.MEMBER);
            assertThat(result.get(WidgetKey.ORG_STATS)).isEqualTo(MinRole.SUPPORTER);
        }
    }

    // ========================================
    // DB レコードあり → 上書き
    // ========================================

    @Nested
    @DisplayName("DB レコードあり: デフォルトを上書き")
    class DbOverride {

        @Test
        @DisplayName("TEAM_LATEST_POSTS の DB 設定で SUPPORTER → PUBLIC に上書きされる")
        void team_DB上書き_PUBLIC() {
            DashboardWidgetRoleVisibilityEntity entity =
                    DashboardWidgetRoleVisibilityEntity.builder()
                            .id(1L)
                            .scopeType(ScopeType.TEAM)
                            .scopeId(TEAM_ID)
                            .widgetKey(WidgetKey.TEAM_LATEST_POSTS.name())
                            .minRole(MinRole.PUBLIC)
                            .updatedBy(10L)
                            .build();
            given(repository.findByScopeTypeAndScopeId(ScopeType.TEAM, TEAM_ID))
                    .willReturn(List.of(entity));

            Map<WidgetKey, MinRole> result = resolver.resolve("TEAM", TEAM_ID);

            assertThat(result.get(WidgetKey.TEAM_LATEST_POSTS)).isEqualTo(MinRole.PUBLIC);
            // 他のキーはデフォルトのまま
            assertThat(result.get(WidgetKey.TEAM_NOTICES)).isEqualTo(MinRole.PUBLIC);
            assertThat(result.get(WidgetKey.TEAM_TODO)).isEqualTo(MinRole.MEMBER);
            assertThat(result).hasSize(8);
        }

        @Test
        @DisplayName("複数 widget の DB 設定が同時に上書きされる")
        void team_複数DB上書き() {
            DashboardWidgetRoleVisibilityEntity e1 = DashboardWidgetRoleVisibilityEntity.builder()
                    .id(1L)
                    .scopeType(ScopeType.TEAM)
                    .scopeId(TEAM_ID)
                    .widgetKey(WidgetKey.TEAM_NOTICES.name())
                    .minRole(MinRole.MEMBER)
                    .updatedBy(10L)
                    .build();
            DashboardWidgetRoleVisibilityEntity e2 = DashboardWidgetRoleVisibilityEntity.builder()
                    .id(2L)
                    .scopeType(ScopeType.TEAM)
                    .scopeId(TEAM_ID)
                    .widgetKey(WidgetKey.TEAM_TODO.name())
                    .minRole(MinRole.PUBLIC)
                    .updatedBy(10L)
                    .build();
            given(repository.findByScopeTypeAndScopeId(ScopeType.TEAM, TEAM_ID))
                    .willReturn(List.of(e1, e2));

            Map<WidgetKey, MinRole> result = resolver.resolve("TEAM", TEAM_ID);

            assertThat(result.get(WidgetKey.TEAM_NOTICES)).isEqualTo(MinRole.MEMBER);
            assertThat(result.get(WidgetKey.TEAM_TODO)).isEqualTo(MinRole.PUBLIC);
            assertThat(result).hasSize(8);
        }
    }

    // ========================================
    // 不正データの扱い
    // ========================================

    @Nested
    @DisplayName("不整合データ: 警告して無視")
    class InvalidData {

        @Test
        @DisplayName("未知の widget_key（存在しない enum 値）はマップに含まれない")
        void 未知widgetKey_無視() {
            DashboardWidgetRoleVisibilityEntity entity =
                    DashboardWidgetRoleVisibilityEntity.builder()
                            .id(99L)
                            .scopeType(ScopeType.TEAM)
                            .scopeId(TEAM_ID)
                            .widgetKey("UNKNOWN_WIDGET_KEY")
                            .minRole(MinRole.MEMBER)
                            .updatedBy(10L)
                            .build();
            given(repository.findByScopeTypeAndScopeId(ScopeType.TEAM, TEAM_ID))
                    .willReturn(List.of(entity));

            Map<WidgetKey, MinRole> result = resolver.resolve("TEAM", TEAM_ID);

            // デフォルト 8 件はそのまま、未知キーは含まれない
            assertThat(result).hasSize(8);
        }

        @Test
        @DisplayName("ADMIN 限定 widget_key の DB 残存データはマップに含まれない")
        void ADMIN限定widget_無視() {
            DashboardWidgetRoleVisibilityEntity entity =
                    DashboardWidgetRoleVisibilityEntity.builder()
                            .id(99L)
                            .scopeType(ScopeType.TEAM)
                            .scopeId(TEAM_ID)
                            .widgetKey(WidgetKey.TEAM_BILLING.name())
                            .minRole(MinRole.MEMBER)
                            .updatedBy(10L)
                            .build();
            given(repository.findByScopeTypeAndScopeId(ScopeType.TEAM, TEAM_ID))
                    .willReturn(List.of(entity));

            Map<WidgetKey, MinRole> result = resolver.resolve("TEAM", TEAM_ID);

            assertThat(result).hasSize(8);
            assertThat(result).doesNotContainKey(WidgetKey.TEAM_BILLING);
        }
    }

    // ========================================
    // PERSONAL スコープ
    // ========================================

    @Nested
    @DisplayName("PERSONAL スコープ")
    class PersonalScope {

        @Test
        @DisplayName("PERSONAL スコープ → 空マップ（DB 検索もしない）")
        void personal_空マップ() {
            Map<WidgetKey, MinRole> result = resolver.resolve("PERSONAL", 0L);
            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // 不変性
    // ========================================

    @Nested
    @DisplayName("戻り値の不変性")
    class Immutability {

        @Test
        @DisplayName("結果マップは不変（put しようとすると例外）")
        void 不変マップ() {
            given(repository.findByScopeTypeAndScopeId(ScopeType.TEAM, TEAM_ID))
                    .willReturn(List.of());

            Map<WidgetKey, MinRole> result = resolver.resolve("TEAM", TEAM_ID);

            assertThatThrownBy(() -> result.put(WidgetKey.TEAM_NOTICES, MinRole.MEMBER))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // ========================================
    // バリデーション
    // ========================================

    @Nested
    @DisplayName("引数バリデーション")
    class ArgValidation {

        @Test
        @DisplayName("scopeType == null → IllegalArgumentException")
        void scopeTypeNull() {
            assertThatThrownBy(() -> resolver.resolve(null, TEAM_ID))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("scopeType == 空文字 → IllegalArgumentException")
        void scopeTypeBlank() {
            assertThatThrownBy(() -> resolver.resolve("  ", TEAM_ID))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("scopeId == null → IllegalArgumentException")
        void scopeIdNull() {
            assertThatThrownBy(() -> resolver.resolve("TEAM", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
