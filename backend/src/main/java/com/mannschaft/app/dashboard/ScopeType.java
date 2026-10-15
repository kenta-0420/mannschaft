package com.mannschaft.app.dashboard;

/**
 * ダッシュボードのスコープ種別。個人・チーム・組織の3レベルを表す。
 */
public enum ScopeType {
    PERSONAL,
    TEAM,
    ORGANIZATION;

    /**
     * URLパスセグメント文字列からScopeTypeに変換する。
     * 複数形（organizations, teams）・単数形（organization, team, personal）・大文字小文字不問。
     */
    public static ScopeType fromPathSegment(String segment) {
        if (segment == null) {
            throw new IllegalArgumentException("ScopeType segment must not be null");
        }
        return switch (segment.toLowerCase()) {
            case "personal" -> PERSONAL;
            case "team", "teams" -> TEAM;
            case "organization", "organizations" -> ORGANIZATION;
            default -> throw new IllegalArgumentException("Unknown ScopeType segment: " + segment);
        };
    }
}
