package com.mannschaft.app.jobmatching.enums;

/**
 * 業務場所種別。
 */
public enum WorkLocationType {

    /** 現地業務（住所必須） */
    ONSITE,

    /** オンライン業務（住所不要） */
    ONLINE,

    /** ハイブリッド（現地 + オンライン併用） */
    HYBRID
}
