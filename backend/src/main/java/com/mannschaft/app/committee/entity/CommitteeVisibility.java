package com.mannschaft.app.committee.entity;

/**
 * 組織メンバーへの委員会公開範囲。
 */
public enum CommitteeVisibility {
    /** 非表示（委員会メンバーのみ閲覧可） */
    HIDDEN,
    /** 名称のみ表示 */
    NAME_ONLY,
    /** 名称と目的を表示 */
    NAME_AND_PURPOSE
}
