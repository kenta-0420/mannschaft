package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.dashboard.MinRole;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.dashboard.WidgetKey;
import com.mannschaft.app.dashboard.entity.DashboardWidgetRoleVisibilityEntity;
import com.mannschaft.app.dashboard.repository.DashboardWidgetRoleVisibilityRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
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
public class WidgetVisibilityResolver {

    private final DashboardWidgetRoleVisibilityRepository repository;

    /**
     * 自己プロキシ参照。{@code @Cacheable} は AOP プロキシ経由でのみ作用するため、
     * 公開メソッド {@link #resolve} から {@link #resolveRaw} を呼ぶ際にプロキシをバイパス
     * しないよう自己注入する。循環参照を避けるため {@link Lazy} を付与する。
     */
    private final WidgetVisibilityResolver self;

    public WidgetVisibilityResolver(DashboardWidgetRoleVisibilityRepository repository,
                                    @Lazy WidgetVisibilityResolver self) {
        this.repository = repository;
        this.self = self;
    }

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

        // キャッシュ層は String キーの Map を保持し、ここで EnumMap に復元する。
        // （Redis JSON シリアライズで EnumMap のキーが String 化される問題の根治。下記 resolveRaw 参照）
        Map<String, MinRole> raw = self().resolveRaw(scopeType, scopeId);
        Map<WidgetKey, MinRole> result = new EnumMap<>(WidgetKey.class);
        for (Map.Entry<String, MinRole> entry : raw.entrySet()) {
            // resolveRaw が書き込むのは必ず既知の WidgetKey 名のみなので valueOf は失敗しない
            result.put(WidgetKey.valueOf(entry.getKey()), entry.getValue());
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * 可視性マップを「{@link WidgetKey#name()} 文字列 → {@link MinRole}」の形で解決しキャッシュする。
     *
     * <p><strong>なぜ {@code Map<WidgetKey, MinRole>} ではなく {@code Map<String, MinRole>} を
     * キャッシュするのか:</strong> {@link org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer}
     * は Map を JSON オブジェクトとしてシリアライズするが、JSON のキーは常に文字列であり、
     * デシリアライズ時に「キーが {@link WidgetKey} 型である」という情報は失われる。
     * その結果、{@code EnumMap<WidgetKey, MinRole>} をキャッシュするとキャッシュ HIT 時に
     * キーが {@code String} 化した {@code Map} が返り、呼び出し側で
     * {@code entry.getKey().name()} や {@code map.get(widgetKey)} が
     * {@code ClassCastException} / 常時 null を引き起こしダッシュボードが断続的に 500 になる。
     * キーを最初から {@code String}（= {@code WidgetKey.name()}）にすることで JSON ラウンドトリップで
     * 形が崩れず、公開メソッド {@link #resolve(String, Long)} で安全に {@code EnumMap} へ復元できる。</p>
     *
     * <p>値の {@link MinRole}（enum）は具象型としてシリアライズ／デシリアライズされるため
     * ラウンドトリップで型が保たれる（キーと異なり値側は型情報が失われない）。</p>
     *
     * <p>キャッシュ: {@code dashboard:widget-visibility} に {@code {scopeType}:{scopeId}} 形式の
     * キーで 300秒保持される。設定更新時は {@link DashboardWidgetVisibilityService} が
     * {@code @CacheEvict} で無効化する。</p>
     */
    @Cacheable(
            value = "dashboard:widget-visibility",
            key = "#scopeType + ':' + #scopeId"
    )
    public Map<String, MinRole> resolveRaw(String scopeType, Long scopeId) {
        ScopeType scope = ScopeType.fromPathSegment(scopeType);

        // 1. アプリ層デフォルトをベースにマップを構築（キーは WidgetKey.name() 文字列）
        Map<String, MinRole> result = new LinkedHashMap<>();
        WidgetDefaultMinRoleMap.getDefaultsForScope(scope)
                .forEach((key, minRole) -> result.put(key.name(), minRole));

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
                result.put(key.name(), entity.getMinRole());
            } catch (IllegalArgumentException ex) {
                log.warn("WidgetVisibilityResolver: 未知のウィジェットキー '{}' を DB で検出 "
                        + "(scopeType={}, scopeId={}, id={})",
                        entity.getWidgetKey(), scopeType, scopeId, entity.getId());
            }
        }

        return result;
    }

    /**
     * 自己プロキシ参照を取得する。{@code @Cacheable} は Spring AOP プロキシ経由でのみ作用するため、
     * 公開メソッド {@link #resolve} から内部の {@link #resolveRaw} を呼ぶ際は
     * 自己注入したプロキシ経由で呼び出す必要がある（直接 {@code this.resolveRaw()} だと
     * プロキシをバイパスしキャッシュが効かない）。
     */
    private WidgetVisibilityResolver self() {
        return self;
    }
}
