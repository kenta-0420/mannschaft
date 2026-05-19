package com.mannschaft.app.publicview.visibility;

import java.util.Objects;

/**
 * 公開ページが対象とするスコープ（チーム / 組織）への参照。
 *
 * <p>F19.1 §3 用語定義 / §4.6.2 サポーターの厳格定義 で用いる「対象スコープ」を表現する不変 DTO。
 * {@link ViewerContext} の構築時、および {@link IdentityVisibilityResolver} の閲覧立場判定時に用いる。</p>
 *
 * <p>{@code scopeType} は {@code "TEAM"} / {@code "ORGANIZATION"} の文字列値を取る。
 * memberships テーブルの {@code scope_type} カラムと一致させること。</p>
 *
 * @param scopeType スコープ種別文字列（{@code "TEAM"} / {@code "ORGANIZATION"}）
 * @param scopeId   スコープ ID（チーム ID または組織 ID）
 */
public record ScopeRef(String scopeType, Long scopeId) {

    /** スコープ種別: チーム。 */
    public static final String TEAM = "TEAM";

    /** スコープ種別: 組織。 */
    public static final String ORGANIZATION = "ORGANIZATION";

    public ScopeRef {
        Objects.requireNonNull(scopeType, "scopeType must not be null");
        Objects.requireNonNull(scopeId, "scopeId must not be null");
    }

    /**
     * TEAM スコープを構築する。
     *
     * @param teamId チーム ID
     * @return TEAM スコープ参照
     */
    public static ScopeRef ofTeam(Long teamId) {
        return new ScopeRef(TEAM, teamId);
    }

    /**
     * ORGANIZATION スコープを構築する。
     *
     * @param organizationId 組織 ID
     * @return ORGANIZATION スコープ参照
     */
    public static ScopeRef ofOrganization(Long organizationId) {
        return new ScopeRef(ORGANIZATION, organizationId);
    }
}
