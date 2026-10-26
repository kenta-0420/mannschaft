package com.mannschaft.app.committee.entity;

/**
 * 伝達の配信範囲。
 */
public enum DistributionScope {
    /** 委員会メンバーのみ */
    COMMITTEE_ONLY,
    /** 親組織のメンバー */
    PARENT_ORG,
    /** 親組織およびその下位組織のメンバー */
    PARENT_ORG_AND_CHILDREN
}
