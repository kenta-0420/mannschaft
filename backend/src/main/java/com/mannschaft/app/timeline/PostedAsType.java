package com.mannschaft.app.timeline;

/**
 * 投稿者の種別。個人またはチーム/組織として投稿可能。
 */
public enum PostedAsType {
    /** 個人ユーザーとして投稿 */
    USER,
    /** チームとして投稿 */
    TEAM,
    /** 組織として投稿 */
    ORGANIZATION
}
