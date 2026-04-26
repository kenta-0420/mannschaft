package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.dashboard.MinRole;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.dashboard.WidgetKey;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * F02.2.1: ウィジェットごとのデフォルト最低必要ロール（min_role）定義。
 *
 * <p>DB の {@code dashboard_widget_role_visibility} にレコードがない場合に
 * 適用されるアプリ層のデフォルト値を一元管理する定数クラス。
 * 設計書 §3「デフォルト min_role 値（アプリ層定義）」のテーブルに準拠。</p>
 *
 * <p>ADMIN／DEPUTY_ADMIN 限定ウィジェット（{@code TEAM_BILLING} / {@code TEAM_PAGE_VIEWS}
 * / {@code ORG_BILLING}）は本マップに含まれない。これらは F02.2 既存の表示ロール仕組みで
 * 別途制御されるため、本機能の管理対象外（{@link #isConfigurable} で false を返す）。</p>
 *
 * <p>個人ダッシュボード（{@code ScopeType.PERSONAL}）のウィジェットは本機能の対象外
 * （自分のデータしか出ないため可視性制御が不要）であり、本マップに含まれない。</p>
 *
 * <p>設計書: docs/features/F02.2.1_dashboard_widget_role_visibility.md §3</p>
 */
public final class WidgetDefaultMinRoleMap {

    /**
     * ウィジェットキー → デフォルト min_role の対応マップ。
     * 不変。本機能で管理対象とする全ウィジェットを網羅する。
     */
    private static final Map<WidgetKey, MinRole> DEFAULTS;

    static {
        Map<WidgetKey, MinRole> map = new EnumMap<>(WidgetKey.class);

        // --- チームダッシュボード ---
        map.put(WidgetKey.TEAM_NOTICES, MinRole.PUBLIC);
        map.put(WidgetKey.TEAM_UPCOMING_EVENTS, MinRole.PUBLIC);
        map.put(WidgetKey.TEAM_TODO, MinRole.MEMBER);
        map.put(WidgetKey.TEAM_PROJECT_PROGRESS, MinRole.MEMBER);
        map.put(WidgetKey.TEAM_ACTIVITY, MinRole.SUPPORTER);
        map.put(WidgetKey.TEAM_LATEST_POSTS, MinRole.SUPPORTER);
        map.put(WidgetKey.TEAM_UNREAD_THREADS, MinRole.MEMBER);
        map.put(WidgetKey.TEAM_MEMBER_ATTENDANCE, MinRole.MEMBER);
        // TEAM_BILLING / TEAM_PAGE_VIEWS は ADMIN 限定のため対象外

        // --- 組織ダッシュボード ---
        map.put(WidgetKey.ORG_TEAM_LIST, MinRole.PUBLIC);
        map.put(WidgetKey.ORG_NOTICES, MinRole.PUBLIC);
        map.put(WidgetKey.ORG_TODO, MinRole.MEMBER);
        map.put(WidgetKey.ORG_PROJECT_PROGRESS, MinRole.MEMBER);
        map.put(WidgetKey.ORG_STATS, MinRole.SUPPORTER);
        // ORG_BILLING は ADMIN 限定のため対象外

        DEFAULTS = Collections.unmodifiableMap(map);
    }

    private WidgetDefaultMinRoleMap() {
        // 定数クラスのためインスタンス化禁止
    }

    /**
     * 指定ウィジェットのデフォルト min_role を取得する。
     *
     * @param key ウィジェットキー
     * @return デフォルト min_role
     * @throws IllegalArgumentException ADMIN 限定ウィジェットなど本機能の管理対象外の場合
     */
    public static MinRole getDefault(WidgetKey key) {
        if (key == null) {
            throw new IllegalArgumentException("WidgetKey must not be null");
        }
        MinRole minRole = DEFAULTS.get(key);
        if (minRole == null) {
            throw new IllegalArgumentException(
                    "WidgetKey '" + key + "' は本機能の管理対象外（ADMIN 限定または PERSONAL スコープ）");
        }
        return minRole;
    }

    /**
     * 指定ウィジェットが本機能の管理対象（min_role 設定変更可能）かを判定する。
     *
     * <p>ADMIN 限定ウィジェット（{@code TEAM_BILLING} / {@code TEAM_PAGE_VIEWS}
     * / {@code ORG_BILLING}）および個人ダッシュボード用ウィジェットは false を返す。</p>
     *
     * @param key ウィジェットキー
     * @return 設定変更可能な場合 true
     */
    public static boolean isConfigurable(WidgetKey key) {
        if (key == null) {
            return false;
        }
        return DEFAULTS.containsKey(key);
    }

    /**
     * 本機能の管理対象となる全ウィジェットキーを返す。
     *
     * <p>GET /widget-visibility エンドポイントで設定一覧を返す際に
     * 「本機能の対象ウィジェット」を列挙する用途で利用する。</p>
     *
     * @return 管理対象ウィジェットキーの集合（不変）
     */
    public static Set<WidgetKey> getAllConfigurableKeys() {
        return Collections.unmodifiableSet(DEFAULTS.keySet());
    }

    /**
     * 指定スコープ種別に属する管理対象ウィジェットのデフォルトマップを返す。
     *
     * <p>{@link WidgetVisibilityResolver} が DB レコードと合成する初期マップとして利用する。</p>
     *
     * @param scopeType スコープ種別。{@code TEAM} または {@code ORGANIZATION}
     * @return ウィジェットキー → デフォルト min_role のマップ（不変）
     */
    public static Map<WidgetKey, MinRole> getDefaultsForScope(ScopeType scopeType) {
        if (scopeType == null) {
            throw new IllegalArgumentException("ScopeType must not be null");
        }
        return DEFAULTS.entrySet().stream()
                .filter(e -> e.getKey().getScopeType() == scopeType)
                .collect(Collectors.collectingAndThen(
                        Collectors.toMap(
                                Map.Entry::getKey,
                                Map.Entry::getValue,
                                (a, b) -> a,
                                () -> new EnumMap<>(WidgetKey.class)),
                        Collections::unmodifiableMap));
    }

    /**
     * 全デフォルト値を返す（不変）。CI 整合性チェック用。
     *
     * @return 全ウィジェットキー → デフォルト min_role のマップ
     */
    public static Map<WidgetKey, MinRole> getAll() {
        return DEFAULTS;
    }
}
