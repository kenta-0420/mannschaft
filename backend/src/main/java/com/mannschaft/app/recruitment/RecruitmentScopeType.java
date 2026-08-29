package com.mannschaft.app.recruitment;

/**
 * F03.11 募集型予約のスコープ種別。
 */
public enum RecruitmentScopeType {
    TEAM,
    ORGANIZATION,
    /** 主体別管理市 Phase 2 の個人札主。scopeId と createdBy は認証済み userId に固定する。 */
    PERSONAL
}
