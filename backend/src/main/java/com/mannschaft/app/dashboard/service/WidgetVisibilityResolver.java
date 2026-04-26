package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.dashboard.MinRole;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.dashboard.WidgetKey;
import com.mannschaft.app.dashboard.entity.DashboardWidgetRoleVisibilityEntity;
import com.mannschaft.app.dashboard.repository.DashboardWidgetRoleVisibilityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * F02.2.1: スコープごとのウィジェット可視性マップを解決するサービス。
 *
 * <p>{@link WidgetDefaultMinRoleMap} のアプリ層デフォルト値と、
 * {@link DashboardWidgetRoleVisibilityRepository} の DB 設定を合成して
 * 「ウィジェット → 最低必要ロール」のマップを返す。DB 上書き設定がない
 * ウィジェットはデフォルト値が適用される。</p>
 *
 * <p>結果は Valkey に 300秒キャッシュする。設定更新時は
 * {@link DashboardWidgetVisibilityService} がキャッシュを {@code @CacheEvict} で無効化する。</p>
 *
 * <p>設計書: docs/features/F02.2.1_dashboard_widget_role_visibility.md §5</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WidgetVisibilityResolver {

    private final DashboardWidgetRoleVisibilityRepository repository;

    /**
     * 指定スコープのウィジェット可視性マップを解決する。
     *
     * <p>戻り値は本機能で管理対象とする全ウィジェット（ADMIN 限定除く）について
     * {@code widget_key → min_role} を含む。レスポンス側で漏れなく判定できるよう
     * デフォルト値で必ず初期化される。</p>
     *
     * <p>キャッシュ: {@code dashboard:widget-visibility} に
     * {@code {scopeType}:{scopeId}} 形式のキーで 300秒保持される。</p>
     *
     * @param scopeType スコープ種別。{@code "TEAM"} または {@code "ORGANIZATION"}
     * @param scopeId   スコープID
     * @return ウィジェットキー → 最低必要ロールのマップ（不変・必ず非 null）
     */
    @Cacheable(
            value = "dashboard:widget-visibility",
            key = "#scopeType + ':' + #scopeId"
    )
    public Map<WidgetKey, MinRole> resolve(String scopeType, Long scopeId) {
        if (scopeType == null || scopeType.isBlank()) {
            throw new IllegalArgumentException("scopeType must not be blank");
        }
        if (scopeId == null) {
            throw new IllegalArgumentException("scopeId must not be null");
        }

        ScopeType scope = ScopeType.fromPathSegment(scopeType);
        if (scope == ScopeType.PERSONAL) {
            // 個人ダッシュボードは本機能の対象外
            return Collections.emptyMap();
        }

        // 1. アプリ層デフォルトをベースにマップを構築
        Map<WidgetKey, MinRole> result = new EnumMap<>(WidgetKey.class);
        result.putAll(WidgetDefaultMinRoleMap.getDefaultsForScope(scope));

        // 2. DB の上書き設定を反映
        List<DashboardWidgetRoleVisibilityEntity> entities =
                repository.findByScopeTypeAndScopeId(scope, scopeId);
        for (DashboardWidgetRoleVisibilityEntity entity : entities) {
            try {
                WidgetKey key = WidgetKey.valueOf(entity.getWidgetKey());
                if (!WidgetDefaultMinRoleMap.isConfigurable(key)) {
                    // ADMIN 限定など管理対象外のキーが残存する不整合データはログのみで無視
                    log.warn("WidgetVisibilityResolver: 管理対象外のウィジェットキー '{}' が DB に残存 "
                            + "(scopeType={}, scopeId={}, id={})", key, scopeType, scopeId, entity.getId());
                    continue;
                }
                result.put(key, entity.getMinRole());
            } catch (IllegalArgumentException ex) {
                log.warn("WidgetVisibilityResolver: 未知のウィジェットキー '{}' を DB で検出 "
                        + "(scopeType={}, scopeId={}, id={})",
                        entity.getWidgetKey(), scopeType, scopeId, entity.getId());
            }
        }

        return Collections.unmodifiableMap(result);
    }
}
