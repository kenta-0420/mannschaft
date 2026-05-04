package com.mannschaft.app.common.visibility;

/**
 * F00 共通可視性判定の {@code AuditLogService} 永続化対象を判定するユーティリティ。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §11.4 / §17.Q12。
 *
 * <p><strong>マスター裁可 C-1</strong> (2026-05-04 / メモ
 * {@code project_f00_phase_a_decisions.md}) の方針に従い、センシティブな
 * {@link StandardVisibility} のみを永続化対象とする:
 * <ul>
 *   <li>全 deny は WARN ログ + Loki + Counter で観測する。
 *   <li>{@link AuditLogService} 永続化は本クラスが {@code true} を返した場合に限る。
 *   <li>cardinality 爆発・ストレージ肥大を抑える目的で、軽量メトリクスと
 *       永続化監査を意図的に二段構えに分離している。
 * </ul>
 */
public final class ResolverAuditPolicy {

    private ResolverAuditPolicy() {
        // ユーティリティ。インスタンス化禁止。
    }

    /**
     * deny 時に {@code AuditLogService} 永続化対象とすべきか判定する。
     *
     * <p>マスター裁可 C-1: {@link StandardVisibility#PRIVATE} /
     * {@link StandardVisibility#CUSTOM_TEMPLATE} /
     * {@link StandardVisibility#ADMINS_ONLY} のみが対象。
     *
     * @param level 解決された可視性レベル ({@code null} 可)
     * @return 永続化すべき場合 true
     */
    public static boolean shouldAuditDeny(StandardVisibility level) {
        if (level == null) {
            return false;
        }
        return level == StandardVisibility.PRIVATE
                || level == StandardVisibility.CUSTOM_TEMPLATE
                || level == StandardVisibility.ADMINS_ONLY;
    }

    /**
     * allow 時に {@code AuditLogService} 永続化対象とすべきか判定する (事後トリアージ用)。
     *
     * <p>マスター裁可 C-1: {@link StandardVisibility#PRIVATE} /
     * {@link StandardVisibility#CUSTOM_TEMPLATE} のみが対象。
     *
     * <p>本判定の実利用は {@code AbstractContentVisibilityResolver} の
     * status 評価が完成する Phase A-1c 以降。本タスク A-6 では deny のみ運用する。
     *
     * @param level 解決された可視性レベル ({@code null} 可)
     * @return 永続化すべき場合 true
     */
    public static boolean shouldAuditAllow(StandardVisibility level) {
        if (level == null) {
            return false;
        }
        return level == StandardVisibility.PRIVATE
                || level == StandardVisibility.CUSTOM_TEMPLATE;
    }
}
