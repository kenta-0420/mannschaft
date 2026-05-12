package com.mannschaft.app.resident.entity;

/**
 * 居住者の死亡状態（F09.15 居住者死亡管理）。
 *
 * resident_registry.death_status カラムに VARCHAR(30) で永続化される。
 */
public enum DeathStatus {

    /** 生存確認済み（デフォルト） */
    ALIVE,

    /** 死亡疑い（presumed_death_score が閾値超え・通報あり等） */
    SUSPECTED,

    /** 死亡確認済み（戸籍・連絡等で確認） */
    CONFIRMED,

    /** 誤検知取消（SUSPECTED から復帰） */
    CANCELLED_FALSE_ALARM
}
