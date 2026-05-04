package com.mannschaft.app.common.visibility;

/**
 * コンテンツの公開状態。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §7.5 完全一致。
 *
 * <p>{@code canView} は「visibility の許可 ∧ status の公開状態」の AND と定義される。
 * Visibility だけでは「下書き / アーカイブ / 削除済み」のような状態軸を表せないため、
 * 本 enum で状態軸も標準化する。
 *
 * <p>各機能の status enum は {@code mapping/} 配下の {@code *StatusMapper} で本 enum に
 * 正規化される。
 */
public enum ContentStatus {

    /**
     * 作成中・未公開。
     *
     * <p>{@code author} および {@code SystemAdmin} のみ可視。
     */
    DRAFT,

    /**
     * 公開予約中 ({@code scheduled_publish_at} が未来)。
     *
     * <p>{@code author} および {@code SystemAdmin} のみ可視。
     */
    SCHEDULED,

    /**
     * 公開中。
     *
     * <p>本状態のときのみ visibility 評価 (StandardVisibility) に進む。
     */
    PUBLISHED,

    /**
     * アーカイブ済み (利用者から非表示)。
     *
     * <p>{@code SystemAdmin} のみ可視。
     */
    ARCHIVED,

    /**
     * 論理削除済み。
     *
     * <p>誰でも不可視 (fail-closed)。SystemAdmin であっても false を返す。
     */
    DELETED
}
