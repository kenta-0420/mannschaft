package com.mannschaft.app.village.entity.enums;

/**
 * 村お祭りの状態（F17.1 Phase 2）。
 *
 * <ul>
 *   <li>{@link #SCHEDULED} — 開始前（starts_at 未到来）</li>
 *   <li>{@link #ACTIVE}    — 期間中（starts_at &le; now &lt; ends_at）</li>
 *   <li>{@link #ENDED}     — 終了済み（ends_at 経過後・自動遷移）</li>
 *   <li>{@link #CANCELLED} — 中止（村長/長老の判断で取りやめ）</li>
 * </ul>
 */
public enum VillageFestivalStatus {
    SCHEDULED,
    ACTIVE,
    ENDED,
    CANCELLED
}
