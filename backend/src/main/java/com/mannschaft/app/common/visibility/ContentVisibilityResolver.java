package com.mannschaft.app.common.visibility;

import java.util.Collection;
import java.util.Set;

/**
 * 個別 {@link ReferenceType reference_type} 用の可視性判定 Strategy。
 *
 * <p>1 reference_type につき 1 つの実装クラスを置き、{@link ContentVisibilityChecker}
 * がコンストラクタで {@link #referenceType()} をキーとしたディスパッチ表を構築する。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.3 完全一致。
 *
 * @param <V> 機能固有の visibility 型 ({@link StandardVisibility} に正規化される前の値)
 */
public interface ContentVisibilityResolver<V> {

    /**
     * この Resolver が担当する {@link ReferenceType} を返す。
     *
     * @return Resolver の担当 reference_type (非 null)
     */
    ReferenceType referenceType();

    /**
     * 単発判定。
     *
     * <p>N+1 を避けるため、複数 ID の判定では {@link #filterAccessible(Collection, Long)}
     * を用いること。
     *
     * @param contentId    判定対象の contentId
     * @param viewerUserId 閲覧者の userId ({@code null} 可、未認証時)
     * @return 閲覧可能なら true
     */
    boolean canView(Long contentId, Long viewerUserId);

    /**
     * バッチ判定。
     *
     * <p>実装は SQL 数 ≦ 2 で完結すべき (1 回の SELECT で必要なメタデータを一括取得し、
     * メモリ上で判定する)。要素順は保証しない。
     *
     * @param contentIds   判定対象の contentId 集合
     * @param viewerUserId 閲覧者の userId ({@code null} 可、未認証時)
     * @return アクセス可能な contentId の Set。空でもよいが {@code null} は返さない
     */
    Set<Long> filterAccessible(Collection<Long> contentIds, Long viewerUserId);

    /**
     * 詳細判定理由を返すデバッグ・監査用 API (任意実装)。
     *
     * <p>デフォルト実装は {@link #canView(Long, Long)} の結果を {@link VisibilityDecision}
     * にラップして返す。個別 Resolver で {@link DenyReason} を厳密に分類したい場合は
     * オーバーライドすること。
     *
     * @param contentId    判定対象の contentId
     * @param viewerUserId 閲覧者の userId
     * @return 判定結果
     */
    default VisibilityDecision decide(Long contentId, Long viewerUserId) {
        boolean ok = canView(contentId, viewerUserId);
        return ok
            ? VisibilityDecision.allow(referenceType(), contentId)
            : VisibilityDecision.deny(referenceType(), contentId, DenyReason.UNSPECIFIED);
    }
}
