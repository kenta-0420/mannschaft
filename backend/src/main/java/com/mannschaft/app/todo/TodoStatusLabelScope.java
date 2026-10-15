package com.mannschaft.app.todo;

/**
 * TODO ステータスラベルのスコープ種別（F02.3.1）。
 */
public enum TodoStatusLabelScope {
    /** システム既定（全ユーザー共通、不変） */
    SYSTEM,
    /** 個人スコープ */
    PERSONAL,
    /** チームスコープ */
    TEAM,
    /** 組織スコープ */
    ORGANIZATION
}
