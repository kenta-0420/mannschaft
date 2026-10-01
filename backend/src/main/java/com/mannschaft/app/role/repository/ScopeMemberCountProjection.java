package com.mannschaft.app.role.repository;

/**
 * 「スコープ（チームまたは組織）ID → user_roles 行数」の軽量射影（F01.2.1 4-B の一覧の人数を一括で数える用）。
 */
public interface ScopeMemberCountProjection {

    /** チーム ID または組織 ID（問い合わせたスコープの側）。 */
    Long getScopeId();

    /** user_roles の行数。 */
    long getMemberCount();
}
