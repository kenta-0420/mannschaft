package com.mannschaft.app.village.entity.enums;

/**
 * 寄合の状態（F17.1 Phase 3-β）。
 *
 * <ul>
 *   <li>{@link #PLANNING}  — 投票受付中（候補日に対して村人が投票している段階）</li>
 *   <li>{@link #CONFIRMED} — 確定済み（幹事が候補日のいずれかを採用）</li>
 *   <li>{@link #CANCELLED} — 中止（幹事の判断で取りやめ）</li>
 * </ul>
 */
public enum VillageMeetupStatus {
    PLANNING,
    CONFIRMED,
    CANCELLED
}
