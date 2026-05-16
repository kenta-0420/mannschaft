package com.mannschaft.app.village.entity.enums;

/**
 * 村練習試合・募集の状態（F17.1 Phase 2）。
 *
 * <ul>
 *   <li>{@link #OPEN}      — 募集中</li>
 *   <li>{@link #CLOSED}    — 締切（自動 or 手動で停止）</li>
 *   <li>{@link #FULFILLED} — 成立（応募が満たされた）</li>
 *   <li>{@link #CANCELLED} — 中止（投稿者判断）</li>
 * </ul>
 */
public enum VillageMatchRecruitStatus {
    OPEN,
    CLOSED,
    FULFILLED,
    CANCELLED
}
