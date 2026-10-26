package com.mannschaft.app.todo;

/**
 * プロジェクトの公開範囲。
 */
public enum ProjectVisibility {
    /** 作成者のみ（個人プロジェクト用） */
    PRIVATE,
    /** メンバーのみ */
    MEMBERS_ONLY,
    /** SUPPORTERも閲覧可 */
    PUBLIC
}
