package com.mannschaft.app.filesharing;

/**
 * F05.5 ファイル共有 — フォルダ／ファイルの「最低可視ロール」（表示制御 B）。
 *
 * <p>スコープ（TEAM / ORGANIZATION）内で、当該フォルダ／ファイルを閲覧・DL できる<b>最低限のロール</b>を表す。
 * カラムが {@code NULL} のときは「所属者全員に可視（SCOPE_AFFILIATED＝従来挙動）」を意味し、本 enum では表現しない
 * （＝判定スキップ）。既存の可視性 enum 全体（F00 の {@code ContentVisibility} 等）は晒さず、ファイル共有の
 * 表示制御に必要な 3 段だけを切り出す。</p>
 *
 * <p>ラダー（強い順）: {@link #ADMINS_AND_ABOVE} &gt; {@link #MEMBERS_AND_ABOVE} &gt; {@link #SUPPORTERS_AND_ABOVE}。
 * 各値は {@link com.mannschaft.app.common.AccessControlService#hasRoleOrAbove} に渡す requiredRoleName に
 * 変換して判定する（{@code ADMINS_AND_ABOVE→"ADMIN"} / {@code MEMBERS_AND_ABOVE→"MEMBER"} /
 * {@code SUPPORTERS_AND_ABOVE→"SUPPORTER"}）。</p>
 *
 * <p>継承規約: ファイル個別値が {@code NULL} のときはフォルダ値を継承し、フォルダも {@code NULL} なら
 * SCOPE_AFFILIATED（判定スキップ＝所属者全員可視）。ファイル経路（詳細取得 / DL URL 発行）は
 * 「ファイル値優先 → フォルダ継承」で評価する。</p>
 */
public enum FileVisibilityRole {

    /** 応援者（SUPPORTER）以上に可視。requiredRoleName = "SUPPORTER"。 */
    SUPPORTERS_AND_ABOVE("SUPPORTER"),

    /** 正会員（MEMBER）以上に可視（応援者は除外）。requiredRoleName = "MEMBER"。 */
    MEMBERS_AND_ABOVE("MEMBER"),

    /** 管理者（ADMIN / 副長 DEPUTY_ADMIN）以上に可視。requiredRoleName = "ADMIN"。 */
    ADMINS_AND_ABOVE("ADMIN");

    private final String requiredRoleName;

    FileVisibilityRole(String requiredRoleName) {
        this.requiredRoleName = requiredRoleName;
    }

    /**
     * {@link com.mannschaft.app.common.AccessControlService#hasRoleOrAbove} に渡す requiredRoleName を返す。
     *
     * @return "ADMIN" / "MEMBER" / "SUPPORTER"
     */
    public String toRequiredRoleName() {
        return requiredRoleName;
    }
}
