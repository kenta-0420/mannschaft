package com.mannschaft.app.recruitment;

/**
 * F03.11 募集型予約: 配信対象種別。
 * Phase 2 で実装される配信スコープの種別を表す。
 */
public enum RecruitmentDistributionTargetType {
    /** スコープ内メンバー */
    MEMBERS,
    /** サポーター */
    SUPPORTERS,
    /** フォロワー */
    FOLLOWERS,
    /** パブリックフィード */
    PUBLIC_FEED
}
