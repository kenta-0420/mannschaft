package com.mannschaft.app.common.visibility;

/**
 * 各機能 Repository が返す軽量射影 (Projection) の共通インターフェース。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.6 完全一致。
 *
 * <p>{@code AbstractContentVisibilityResolver} は本 IF を経由して
 * 機能側 Projection から visibility 判定に必要な属性を取り出す。
 *
 * <p>各機能 (例: {@code BlogPostVisibilityProjection}) はこの IF を実装し、
 * Spring Data JDBC / JPA の Projection 機能で SQL 1 本での射影取得を実現する。
 *
 * <p><strong>{@link #visibility()} の戻り型について</strong>:
 * 機能側 enum の型を Projection 側で固定するため戻り型は {@link Object} としている。
 * Resolver 側で機能側 enum 型へキャストして {@code Mapper.toStandard(...)} に渡す。
 */
public interface VisibilityProjection {

    /** コンテンツの ID. */
    Long id();

    /**
     * スコープ種別。{@code "TEAM"} または {@code "ORGANIZATION"} を返すこと。
     *
     * @return スコープ種別文字列
     */
    String scopeType();

    /**
     * スコープ ID (team_id または organization_id)。
     *
     * @return スコープ ID
     */
    Long scopeId();

    /**
     * 作成者の user_id。
     *
     * <p>システム生成コンテンツ等で作成者が存在しない場合は {@code null} を返してよい。
     *
     * @return 作成者 user_id ({@code null} 可)
     */
    Long authorUserId();

    /**
     * カスタムテンプレート ID。
     *
     * <p>visibility が {@code CUSTOM_TEMPLATE} のときのみ非 null。それ以外では {@code null}。
     *
     * @return visibility_template_id ({@code null} 可)
     */
    Long visibilityTemplateId();

    /**
     * 機能側 visibility enum 値。
     *
     * <p>各機能側で個別の enum 型 (例: {@code cms.Visibility}) のインスタンスを返す。
     * Resolver 側で機能側 enum 型へキャストし、対応 Mapper で StandardVisibility に
     * 正規化する。
     *
     * @return 機能側 enum 値
     */
    Object visibility();
}
