package com.mannschaft.app.village.entity.enums;

/**
 * 村練習試合・募集のカテゴリ（F17.1 Phase 2）。
 *
 * <ul>
 *   <li>{@link #PRACTICE_MATCH} — 練習試合の対戦相手募集</li>
 *   <li>{@link #REFEREE}        — 審判募集</li>
 *   <li>{@link #VENUE}          — 会場提供募集</li>
 *   <li>{@link #OTHER}          — その他（マネージャー等）</li>
 * </ul>
 */
public enum VillageMatchRecruitCategory {
    PRACTICE_MATCH,
    REFEREE,
    VENUE,
    OTHER
}
