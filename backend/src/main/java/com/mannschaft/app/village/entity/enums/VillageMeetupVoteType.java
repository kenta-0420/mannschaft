package com.mannschaft.app.village.entity.enums;

/**
 * 寄合投票の選択肢（F17.1 Phase 3-β）。
 *
 * <ul>
 *   <li>{@link #AVAILABLE}   — 行ける</li>
 *   <li>{@link #MAYBE}       — たぶん行ける</li>
 *   <li>{@link #UNAVAILABLE} — 行けない</li>
 * </ul>
 */
public enum VillageMeetupVoteType {
    AVAILABLE,
    MAYBE,
    UNAVAILABLE
}
