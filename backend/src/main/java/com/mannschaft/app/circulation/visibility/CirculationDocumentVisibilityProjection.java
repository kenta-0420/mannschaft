package com.mannschaft.app.circulation.visibility;

import com.mannschaft.app.circulation.CirculationStatus;
import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.common.visibility.VisibilityProjection;

/**
 * F00 Phase C — 回覧文書 ({@link com.mannschaft.app.circulation.entity.CirculationDocumentEntity}) 用 Projection。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.6 / §7.5 / §15 D-13/D-14/D-16。</p>
 *
 * <p>{@code circulation_documents} テーブルの {@code @SQLRestriction("deleted_at IS NULL")} により論理削除済の
 * 行は射影段階で除外されるため、{@link com.mannschaft.app.common.visibility.ContentStatus#DELETED} を
 * Projection で再度区別する必要は無い（取得不可 → fail-closed の自然な振る舞いに従う）。</p>
 *
 * <p>回覧板は機能側に visibility 概念を持たない（§12.3.1 の「visibility 概念新設機能」群）。本基盤では
 * 軍議で確定した案 A に基づき、配信先 ACL ({@code circulation_recipients} テーブル) を参照する CUSTOM
 * 経路として扱う。よって {@link #visibility()} は常に {@link StandardVisibility#CUSTOM} を返し、Resolver の
 * {@code evaluateCustom} で recipients 判定を行う。</p>
 *
 * <p>機能側 visibility enum を持たないため、Resolver の総称型 {@code <V>} には {@link StandardVisibility}
 * 自体を割り当て、{@code toStandard} は恒等写像となる。</p>
 *
 * @param id              circulation_documents.id
 * @param scopeType       {@code "TEAM"} または {@code "ORGANIZATION"}
 * @param scopeId         team_id または organization_id
 * @param authorUserId    circulation_documents.created_by（fail-closed のため null は弾かれる）
 * @param status          circulation_documents.status（status 軸正規化に利用）
 */
public record CirculationDocumentVisibilityProjection(
        Long id,
        String scopeType,
        Long scopeId,
        Long authorUserId,
        CirculationStatus status) implements VisibilityProjection {

    @Override
    public Long visibilityTemplateId() {
        return null;
    }

    /**
     * 回覧板は配信先 ACL 判定固定であるため、常に {@link StandardVisibility#CUSTOM} を返す。
     *
     * <p>{@link com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver} の
     * {@code resolveLevelSafely} はこの戻り値をそのまま {@code toStandard} 経由で
     * {@link StandardVisibility} に解決する。</p>
     */
    @Override
    public Object visibility() {
        return StandardVisibility.CUSTOM;
    }
}
