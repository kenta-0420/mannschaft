package com.mannschaft.app.shiftbudget;

/**
 * F08.7 シフト予算消化記録のステータス。
 *
 * <p>設計書 F08.7 (v1.2) §5.3 / §11 / §11.1 に準拠。</p>
 *
 * <p>遷移ルール:</p>
 * <ul>
 *   <li>{@link #PLANNED} → {@link #CONFIRMED}（月次締め時）</li>
 *   <li>{@link #PLANNED} → {@link #CANCELLED}（シフト取消・再公開・スロット削除等）</li>
 *   <li>{@link #CONFIRMED} → 不変（経理整合性確保。月次締め後の遅延キャンセルは
 *       新規 {@link #CANCELLED} 行を INSERT する取消仕訳パターンで表現）</li>
 *   <li>{@link #CANCELLED} → 不変（監査証跡として残置）</li>
 * </ul>
 */
public enum ShiftBudgetConsumptionStatus {

    /** 計画（シフト公開済み・月次締め前） */
    PLANNED,

    /** 確定（月次締め完了・経理連携済み） */
    CONFIRMED,

    /** 取消（シフト取消・再公開・遅延キャンセル等） */
    CANCELLED
}
