package com.mannschaft.app.shiftbudget;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F08.7 シフト-予算-TODO 連携機能のエラーコード定義。
 *
 * <p>Phase 9-α: 逆算 API ({@code POST /api/v1/shift-budget/calc/required-slots}) のバリデーション
 * ・フィーチャーフラグ系（001〜009）。</p>
 *
 * <p>Phase 9-β: シフト予算割当 / 消化記録 CRUD 系（010〜019）。
 * 設計書 F08.7 (v1.2) §5.2 HAS_CONSUMPTIONS 運用ルール / §8 権限・ロール / §9.2 楽観的ロックに対応。</p>
 *
 * <p>後続の Phase 9-γ/δ で TODO 紐付・警告・月次締め系のコードを追加予定。</p>
 *
 * <p>HTTP ステータスマッピングは
 * {@link com.mannschaft.app.common.GlobalExceptionHandler#ERROR_CODE_STATUS_MAP} に登録する。</p>
 */
@Getter
@RequiredArgsConstructor
public enum ShiftBudgetErrorCode implements ErrorCode {

    /** フィーチャーフラグ OFF 時の API 呼出 (HTTP 503) */
    FEATURE_DISABLED("SHIFT_BUDGET_001",
            "シフト予算機能は無効化されています", Severity.WARN),

    /** position_required_counts が空配列 (HTTP 400) */
    EMPTY_POSITION_LIST("SHIFT_BUDGET_002",
            "ポジション別必要人数を1件以上指定してください", Severity.WARN),

    /** position_required_counts に重複する position_id がある (HTTP 400) */
    DUPLICATE_POSITION_ID("SHIFT_BUDGET_003",
            "重複するポジションIDが指定されています", Severity.WARN),

    /** required_count が 0 以下 (HTTP 400) */
    INVALID_REQUIRED_COUNT("SHIFT_BUDGET_004",
            "必要人数は1以上で指定してください", Severity.WARN),

    /** スロット時間が範囲外 (HTTP 400) */
    INVALID_SLOT_HOURS("SHIFT_BUDGET_005",
            "スロット時間は0.25時間以上24時間以下で指定してください", Severity.WARN),

    /** EXPLICIT モードで avg_hourly_rate が未指定 (HTTP 400) */
    MISSING_EXPLICIT_RATE("SHIFT_BUDGET_006",
            "EXPLICITモードでは avg_hourly_rate の指定が必須です", Severity.WARN),

    /** POSITION_AVG モードで position_required_counts が未指定 (HTTP 400) */
    MISSING_POSITION_COUNTS("SHIFT_BUDGET_007",
            "POSITION_AVGモードでは position_required_counts の指定が必須です", Severity.WARN),

    /** チームが存在しない / 組織スコープ不一致 (HTTP 404 IDOR 対策) */
    TEAM_NOT_FOUND("SHIFT_BUDGET_008",
            "対象のチームが見つかりません", Severity.WARN),

    /** 予算金額が負数 (HTTP 400) */
    INVALID_BUDGET_AMOUNT("SHIFT_BUDGET_009",
            "予算額は0以上で指定してください", Severity.WARN),

    // ====================================================================
    // Phase 9-β: シフト予算割当 / 消化記録 CRUD 系（010〜019）
    // ====================================================================

    /** 割当が見つからない / 別組織のIDを参照（IDOR対策で 404 統一） (HTTP 404) */
    ALLOCATION_NOT_FOUND("SHIFT_BUDGET_010",
            "対象のシフト予算割当が見つかりません", Severity.WARN),

    /** 同一スコープ（org × team × project × category × period）に生存割当が既存 (HTTP 409) */
    ALLOCATION_ALREADY_EXISTS("SHIFT_BUDGET_011",
            "同一スコープのシフト予算割当が既に存在します", Severity.WARN),

    /** PLANNED な消化レコードが残存し割当を削除できない (HTTP 409) */
    HAS_CONSUMPTIONS_PLANNED("SHIFT_BUDGET_012",
            "計画中の消化レコードが残存しているため割当を削除できません", Severity.WARN),

    /** CONFIRMED な消化レコードが残存し割当を削除できない (HTTP 409) */
    HAS_CONSUMPTIONS_CONFIRMED("SHIFT_BUDGET_013",
            "確定済みの消化レコードが残存しているため割当を削除できません", Severity.WARN),

    /** 楽観的ロック競合（version 不一致） (HTTP 409) */
    OPTIMISTIC_LOCK_CONFLICT("SHIFT_BUDGET_014",
            "他の操作と競合したため処理を中断しました。再読込のうえ再試行してください", Severity.WARN),

    /** period_start > period_end など期間指定が不正 (HTTP 400) */
    INVALID_PERIOD("SHIFT_BUDGET_015",
            "期間指定が不正です（開始日が終了日を超えています）", Severity.WARN),

    /** 割当額が負数 (HTTP 400) */
    INVALID_ALLOCATED_AMOUNT("SHIFT_BUDGET_016",
            "割当額は0以上で指定してください", Severity.WARN),

    /** CONFIRMED な消化レコードに対する更新試行 (HTTP 409) */
    CONFIRMED_RECORD_IMMUTABLE("SHIFT_BUDGET_017",
            "確定済みの消化レコードは更新できません", Severity.WARN),

    /** BUDGET_VIEW 権限が不足 (HTTP 403) */
    BUDGET_VIEW_REQUIRED("SHIFT_BUDGET_018",
            "シフト予算の閲覧権限が必要です", Severity.WARN),

    /** BUDGET_MANAGE 権限が不足 (HTTP 403) */
    BUDGET_MANAGE_REQUIRED("SHIFT_BUDGET_019",
            "シフト予算の管理権限が必要です", Severity.WARN),

    // ====================================================================
    // Phase 9-γ: TODO/プロジェクト 予算紐付系（020〜029）
    // ====================================================================

    /**
     * 紐付対象の指定が不正（project_id と todo_id がどちらも NULL、または両方指定）
     * 設計書 §5.4 chk_tbl_target_xor (HTTP 400)
     */
    INVALID_LINK_TARGET("SHIFT_BUDGET_020",
            "紐付対象は project_id と todo_id のどちらか一方のみ指定してください", Severity.WARN),

    /**
     * 紐付方式の指定が不正（link_amount と link_percentage が同時指定）
     * 設計書 §5.4 chk_tbl_link_xor (HTTP 400)
     */
    INVALID_LINK_PARAMETER("SHIFT_BUDGET_021",
            "link_amount と link_percentage は同時指定できません", Severity.WARN),

    /**
     * 同一 (project_id, allocation_id) または (todo_id, allocation_id) で紐付済み (HTTP 409)
     */
    LINK_ALREADY_EXISTS("SHIFT_BUDGET_022",
            "同一の対象と割当の組み合わせで紐付が既に存在します", Severity.WARN),

    /** 紐付が見つからない / 別組織のIDを参照（IDOR対策で 404 統一） (HTTP 404) */
    LINK_NOT_FOUND("SHIFT_BUDGET_023",
            "対象の予算紐付が見つかりません", Severity.WARN),

    /** プロジェクトが見つからない / 別組織所属（IDOR対策で 404 統一） (HTTP 404) */
    PROJECT_NOT_FOUND("SHIFT_BUDGET_024",
            "対象のプロジェクトが見つかりません", Severity.WARN),

    /** TODO が見つからない / 別組織所属（IDOR対策で 404 統一） (HTTP 404) */
    TODO_NOT_FOUND("SHIFT_BUDGET_025",
            "対象の TODO が見つかりません", Severity.WARN),

    /**
     * TODO 紐付の権限が不足（todo/project の編集権 = ADMIN_OR_ABOVE が必要）。
     * 設計書 §6.1: MANAGE_TODO + BUDGET_VIEW (HTTP 403)
     */
    LINK_PERMISSION_REQUIRED("SHIFT_BUDGET_026",
            "TODO/プロジェクトの編集権限とシフト予算の閲覧権限が必要です", Severity.WARN),

    // ====================================================================
    // Phase 9-δ: 警告・月次締め・BUDGET_ADMIN クリーンカット系（027〜039）
    // ====================================================================

    /**
     * BUDGET_ADMIN 権限が不足（v1.2 クリーンカット方式の中核）。
     * <p>設計書 §8.2: 予算割当の CRUD / 警告承認 / 月次締めバッチ起動など F08.7 の管理操作で必要 (HTTP 403)</p>
     */
    BUDGET_ADMIN_REQUIRED("SHIFT_BUDGET_027",
            "シフト予算管理権限 (BUDGET_ADMIN) が必要です", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
