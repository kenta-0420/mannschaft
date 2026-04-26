package com.mannschaft.app.shift.assignment;

import com.mannschaft.app.shift.AssignmentStrategyType;

/**
 * シフト自動割当アルゴリズムの Strategy インターフェース。
 * 各アルゴリズム実装はこのインターフェースを実装して @Component として登録する。
 */
public interface ShiftAssignmentStrategy {

    /**
     * このストラテジーが担当するアルゴリズム種別を返す。
     *
     * @return アルゴリズム種別
     */
    AssignmentStrategyType getStrategyType();

    /**
     * 自動割当を実行して結果を返す。
     *
     * @param context 割当コンテキスト
     * @return 割当結果（提案 + 警告）
     */
    AssignmentResult assign(AssignmentContext context);
}
