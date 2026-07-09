package com.mannschaft.app.analytics;

/**
 * ページビュー計測（F10.8 アクセス解析）の閲覧対象種別。
 *
 * <p>DB カラム {@code page_view_logs.content_type} は {@code VARCHAR(20)} で enum 名を保存する。
 * ID を持たない種別（{@code PAGE} 等）は {@code content_id = 0} を採る（設計書 §4.2・NOT NULL 制約下の
 * {@code scope_id=0} 前例に整合）。</p>
 */
public enum PageViewContentType {
    /** 記事（ブログ・お知らせ等）。 */
    ARTICLE,
    /** 活動記録。 */
    ACTIVITY,
    /** ID を持たない汎用ページ（{@code content_id = 0}）。 */
    PAGE,
    /** チームトップページ。 */
    TEAM
}
