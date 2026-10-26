package com.mannschaft.app.cms.media;

import com.mannschaft.app.common.storage.quota.StorageScopeType;

/**
 * ブログ記事が属するストレージスコープ（{@code blog/{scopeType}/{scopeId}/} の前 2 セグメント）。
 *
 * <p>本文メディアの越境防止（{@link BlogBodyMediaResolver} の関門1）は
 * 「投稿自身のスコープ」を基準に判定する。そのスコープ導出を会員経路
 * （{@code BlogPostService}）と公開経路（{@code PublicPostQueryService}）で
 * <b>同一の実装</b>に寄せるために本レコードを設ける。片方だけ導出を誤ると、
 * 一方の経路でだけ画像が出ない／越境判定がずれるという発見しにくい不整合になる。</p>
 *
 * <p>アップロード時のキー生成（{@code BlogMediaService}）と同じ規則に従うこと。</p>
 */
public record BlogMediaScope(StorageScopeType scopeType, Long scopeId) {

    /**
     * 記事のスコープ列から所属スコープを導出する。
     *
     * <p>優先順は チーム → 組織 → 個人。いずれも未設定の記事は解決対象外として {@code null} を返す。</p>
     *
     * @param teamId         チーム ID（チーム記事以外は null）
     * @param organizationId 組織 ID（組織記事以外は null）
     * @param userId         個人ブログの所有者ユーザー ID（個人記事以外は null）
     * @return 所属スコープ。判定できない場合は {@code null}
     */
    public static BlogMediaScope of(Long teamId, Long organizationId, Long userId) {
        if (teamId != null) {
            return new BlogMediaScope(StorageScopeType.TEAM, teamId);
        }
        if (organizationId != null) {
            return new BlogMediaScope(StorageScopeType.ORGANIZATION, organizationId);
        }
        if (userId != null) {
            return new BlogMediaScope(StorageScopeType.PERSONAL, userId);
        }
        return null;
    }
}
