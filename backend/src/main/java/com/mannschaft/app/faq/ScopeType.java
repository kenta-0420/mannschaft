package com.mannschaft.app.faq;

/**
 * FAQ ドメインのスコープ種別。公開FAQはチーム単位または組織単位で管理する。
 *
 * <p>共通の {@code ScopeType} は存在せず（{@code bulletin} / {@code membership} /
 * {@code scopefolder} など各ドメインがそれぞれ独自に定義している）、FAQ で必要なのは
 * TEAM / ORGANIZATION の2値のみであるため、{@code bulletin.ScopeType} を参考に
 * faq ドメイン内へ最小限で定義する。</p>
 */
public enum ScopeType {
    /** チーム単位の公開FAQ */
    TEAM,
    /** 組織単位の公開FAQ */
    ORGANIZATION;

    /**
     * URL パスセグメント（複数形 or 単数形）から ScopeType を取得する。
     *
     * <p>RESTful な URL（{@code /teams/{id}/faqs} 等）から受け取ったパスセグメントを
     * enum に変換する際に使用する。</p>
     *
     * <p>対応表:
     * <ul>
     *   <li>{@code "teams"} / {@code "team"} → {@link #TEAM}</li>
     *   <li>{@code "organizations"} / {@code "organization"} → {@link #ORGANIZATION}</li>
     * </ul>
     * </p>
     *
     * @param pathSegment URL パスセグメント（大文字小文字・単複問わず）
     * @return 対応する ScopeType
     * @throws IllegalArgumentException 対応する値が存在しない場合
     */
    public static ScopeType fromPathSegment(String pathSegment) {
        if (pathSegment == null) {
            throw new IllegalArgumentException("scopeType must not be null");
        }
        String normalized = pathSegment.toUpperCase();
        return switch (normalized) {
            case "TEAMS", "TEAM" -> TEAM;
            case "ORGANIZATIONS", "ORGANIZATION" -> ORGANIZATION;
            default -> throw new IllegalArgumentException("Unknown scopeType: " + pathSegment);
        };
    }
}
