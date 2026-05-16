package com.mannschaft.app.bulletin;

/**
 * スコープ種別。カテゴリ・スレッドの適用範囲を識別する。
 */
public enum ScopeType {
    ORGANIZATION,
    TEAM,
    PERSONAL,
    /**
     * 村スコープ（F17.1 Phase 3）。{@code scope_village_id} で村の UUIDv7 を保持し、
     * {@code scope_id} は 0 を入れる（NOT NULL 制約のため）。
     */
    VILLAGE;

    /**
     * URL パスセグメント（複数形 or 単数形）から ScopeType を取得する。
     *
     * <p>RESTful な URL 設計では {@code /organizations/{id}} のように複数形を使うため、
     * コントローラから受け取ったパスセグメントを enum に変換する際に使用する。</p>
     *
     * <p>対応表:
     * <ul>
     *   <li>{@code "organizations"} / {@code "organization"} → {@link #ORGANIZATION}</li>
     *   <li>{@code "teams"} / {@code "team"} → {@link #TEAM}</li>
     *   <li>{@code "personal"} → {@link #PERSONAL}</li>
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
            case "ORGANIZATIONS", "ORGANIZATION" -> ORGANIZATION;
            case "TEAMS", "TEAM" -> TEAM;
            case "PERSONAL" -> PERSONAL;
            case "VILLAGES", "VILLAGE" -> VILLAGE;
            default -> throw new IllegalArgumentException("Unknown scopeType: " + pathSegment);
        };
    }
}
