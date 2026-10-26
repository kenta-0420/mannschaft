package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.dashboard.MinRole;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.dashboard.SwipeWidgetKey;
import com.mannschaft.app.dashboard.ViewerRole;
import com.mannschaft.app.dashboard.entity.DashboardWidgetRoleVisibilityEntity;
import com.mannschaft.app.dashboard.repository.DashboardWidgetRoleVisibilityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * F22.1: 横スワイプ・ダッシュボードの SWIPE_* ウィジェットの可視性（min_role）を解決する。
 *
 * <p>F02.2.1 の {@link WidgetVisibilityResolver} とは独立して動作する。理由は、あちらは
 * {@link WidgetDefaultMinRoleMap} の 13 キー契約（厳密にテストされている）に閉じており、
 * SWIPE_* キーを混ぜるとその契約・テストを破壊するため。本リゾルバは同じ
 * {@code dashboard_widget_role_visibility} テーブルを <b>文字列キー</b>で参照し、
 * DB に上書きがあればそれを、なければ MEMBER デフォルトを適用する（04 §6）。</p>
 *
 * <p>本機能の 8 枚はすべてデフォルト {@code min_role = MEMBER}（管理者限定を含めない方針）。
 * チーム/組織ダッシュボードのエンドポイントは所属検証（{@code checkMembership}）を通すため、
 * MEMBER 閲覧者には全ウィジェットが可視、SUPPORTER 閲覧者には MEMBER ウィジェットが
 * サーバー側でスキップ（フィールド省略）される。</p>
 *
 * <p>設計書: docs/features/F22.1_swipe_scope_dashboard/02_api_design.md §3.3 /
 * 04_widgets.md §6</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SwipeWidgetVisibilityResolver {

    private final DashboardWidgetRoleVisibilityRepository repository;

    /** SWIPE_* ウィジェットの既定 min_role（04 §6: 全 8 枚 MEMBER）。 */
    private static final MinRole DEFAULT_MIN_ROLE = MinRole.MEMBER;

    /**
     * 指定スコープの SWIPE_* ウィジェット可視性マップを解決する。
     *
     * <p>DB の {@code dashboard_widget_role_visibility} に SWIPE_* キーの上書きがあれば反映し、
     * なければ {@link #DEFAULT_MIN_ROLE}（MEMBER）を適用する。当該スコープに属する
     * SWIPE_* キーのみを返す。</p>
     *
     * @param scopeType スコープ種別（{@code "TEAM"} / {@code "ORGANIZATION"}）
     * @param scopeId   スコープ ID
     * @return ウィジェットキー → 最低必要ロールのマップ（必ず非 null）
     */
    public Map<SwipeWidgetKey, MinRole> resolve(String scopeType, Long scopeId) {
        ScopeType scope = ScopeType.fromPathSegment(scopeType);

        Map<SwipeWidgetKey, MinRole> result = new EnumMap<>(SwipeWidgetKey.class);
        for (SwipeWidgetKey key : SwipeWidgetKey.values()) {
            if (key.getScopeType() == scope) {
                result.put(key, DEFAULT_MIN_ROLE);
            }
        }

        // DB 上書き設定を反映（テーブルは F02.2.1 と共有・文字列キーで照合）
        List<DashboardWidgetRoleVisibilityEntity> overrides =
                repository.findByScopeTypeAndScopeId(scope, scopeId);
        for (DashboardWidgetRoleVisibilityEntity entity : overrides) {
            SwipeWidgetKey key = parseOrNull(entity.getWidgetKey());
            if (key != null && key.getScopeType() == scope) {
                result.put(key, entity.getMinRole());
            }
        }
        return result;
    }

    /**
     * SWIPE_* ウィジェットが閲覧者ロールから可視であればデータをそのまま、不可視なら null を返す。
     *
     * <p>管理者（{@link ViewerRole#isAdminOrAbove()}）は常に可視。それ以外は
     * {@code viewerRole.isAtLeast(min_role)} で判定する。{@code data} が null
     * （実体が無い場合など）はそのまま null を返す。</p>
     *
     * @param viewerRole    閲覧者ロール
     * @param visibilityMap {@link #resolve} の結果
     * @param key           対象ウィジェットキー
     * @param data          ウィジェットデータ
     * @param <T>           データ型
     * @return 可視なら data、不可視なら null
     */
    public <T> T filterIfVisible(ViewerRole viewerRole,
                                 Map<SwipeWidgetKey, MinRole> visibilityMap,
                                 SwipeWidgetKey key,
                                 T data) {
        if (data == null) {
            return null;
        }
        if (viewerRole != null && viewerRole.isAdminOrAbove()) {
            return data;
        }
        MinRole minRole = visibilityMap.getOrDefault(key, DEFAULT_MIN_ROLE);
        if (viewerRole != null && viewerRole.isAtLeast(minRole)) {
            return data;
        }
        return null;
    }

    private static SwipeWidgetKey parseOrNull(String widgetKey) {
        if (widgetKey == null || !widgetKey.startsWith("SWIPE_")) {
            return null;
        }
        try {
            return SwipeWidgetKey.valueOf(widgetKey);
        } catch (IllegalArgumentException ex) {
            log.warn("SwipeWidgetVisibilityResolver: 未知の SWIPE ウィジェットキー '{}' を DB で検出", widgetKey);
            return null;
        }
    }
}
