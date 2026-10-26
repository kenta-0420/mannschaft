package com.mannschaft.app.dashboard;

/**
 * F22.1: 横スワイプ・ダッシュボードの厳選 8 ウィジェットの widget_key。
 *
 * <p>F02.2 の既存 {@link WidgetKey}（チーム/組織の詳細ダッシュボードページ用）とは
 * <b>名前空間を分離</b>する。横スワイプパネルは「別ビュー・別カスタマイズ単位」であり、
 * F02.2 の可視性設定と共有すると片方の非表示がもう片方に波及するため、
 * {@code SWIPE_} プレフィックスの新キーで分離する（設計書 04_widgets.md §2.1）。</p>
 *
 * <p>これらのキーは {@code dashboard_widget_settings} / {@code dashboard_widget_role_visibility}
 * の {@code widget_key} 列にそのまま格納できる（テーブル変更不要・04 §6）。
 * F02.2.1 の {@link com.mannschaft.app.dashboard.service.WidgetDefaultMinRoleMap}
 * には含めない（あちらの 13 キー契約を侵さないため）。本機能専用の可視性判定は
 * {@link com.mannschaft.app.dashboard.service.SwipeWidgetVisibilityResolver} が担う。</p>
 *
 * <p>本機能の 8 枚はすべて {@code min_role = MEMBER}（管理者限定ウィジェットを含めない方針・04 §6）。</p>
 *
 * <p>設計書: docs/features/F22.1_swipe_scope_dashboard/04_widgets.md §3 / §4</p>
 */
public enum SwipeWidgetKey {

    // --- チームパネル（04 §3）---
    SWIPE_TEAM_UPCOMING(ScopeType.TEAM),
    SWIPE_TEAM_TIMELINE(ScopeType.TEAM),
    SWIPE_TEAM_BULLETIN(ScopeType.TEAM),
    SWIPE_TEAM_BLOG(ScopeType.TEAM),
    SWIPE_TEAM_CHAT(ScopeType.TEAM),
    SWIPE_TEAM_CALENDAR(ScopeType.TEAM),
    SWIPE_TEAM_TODO(ScopeType.TEAM),
    SWIPE_TEAM_ACTION_REQUIRED(ScopeType.TEAM),

    // --- 組織パネル（04 §4）---
    SWIPE_ORG_UPCOMING(ScopeType.ORGANIZATION),
    SWIPE_ORG_TIMELINE(ScopeType.ORGANIZATION),
    SWIPE_ORG_BULLETIN(ScopeType.ORGANIZATION),
    SWIPE_ORG_BLOG(ScopeType.ORGANIZATION),
    SWIPE_ORG_CHAT(ScopeType.ORGANIZATION),
    SWIPE_ORG_CALENDAR(ScopeType.ORGANIZATION),
    SWIPE_ORG_TODO(ScopeType.ORGANIZATION),
    SWIPE_ORG_ACTION_REQUIRED(ScopeType.ORGANIZATION);

    private final ScopeType scopeType;

    SwipeWidgetKey(ScopeType scopeType) {
        this.scopeType = scopeType;
    }

    public ScopeType getScopeType() {
        return scopeType;
    }
}
