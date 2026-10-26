package com.mannschaft.app.resident.entity;

/**
 * 居住者の居住実態区分（F09.16 居住実態管理）。
 *
 * resident_registry.occupancy_status カラムに VARCHAR(30) で永続化される。
 */
public enum OccupancyStatus {

    /** 区分所有者本人が居住 */
    OWNER_OCCUPIED,

    /** 第三者に賃貸中 */
    RENTED_OUT,

    /** 空室 */
    VACANT,

    /** セカンドハウス・別荘扱い */
    SECONDARY_HOME,

    /** 未確認（デフォルト） */
    UNKNOWN
}
