package com.mannschaft.app.repairplan;

/**
 * 修繕計画項目のステータス（F08.8 Phase 1 案5）。
 *
 * <ul>
 *   <li>PLANNED — 計画段階</li>
 *   <li>IN_PROGRESS — 実施中</li>
 *   <li>COMPLETED — 完了</li>
 *   <li>DEFERRED — 延期</li>
 *   <li>CANCELED — 中止</li>
 * </ul>
 */
public enum RepairPlanItemStatus {
    PLANNED,
    IN_PROGRESS,
    COMPLETED,
    DEFERRED,
    CANCELED
}
