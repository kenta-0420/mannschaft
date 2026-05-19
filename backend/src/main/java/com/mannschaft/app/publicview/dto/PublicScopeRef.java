package com.mannschaft.app.publicview.dto;

/**
 * 公開 DTO 用のスコープ参照（チーム / 組織）。
 *
 * <p>F19.1 §6.3 「PII 完全分離」原則に従う公開専用 DTO。
 * 認証済み API の DTO とは共有せず、Defense in Depth を担保する。</p>
 *
 * <p>投稿の所属スコープへの軽量参照として用いる。</p>
 *
 * @param scopeType {@code "TEAM"} または {@code "ORGANIZATION"}（{@code null} 不可）
 * @param scopeId   スコープ ID（{@code null} 不可）
 * @param scopeName スコープ名称（公開可否は呼び出し側で確認済み前提）
 */
public record PublicScopeRef(
        String scopeType,
        Long scopeId,
        String scopeName
) {

    /** スコープ種別: チーム。 */
    public static final String TEAM = "TEAM";

    /** スコープ種別: 組織。 */
    public static final String ORGANIZATION = "ORGANIZATION";

    /**
     * TEAM 種別の参照を生成する。
     */
    public static PublicScopeRef ofTeam(Long teamId, String teamName) {
        return new PublicScopeRef(TEAM, teamId, teamName);
    }

    /**
     * ORGANIZATION 種別の参照を生成する。
     */
    public static PublicScopeRef ofOrganization(Long orgId, String orgName) {
        return new PublicScopeRef(ORGANIZATION, orgId, orgName);
    }
}
