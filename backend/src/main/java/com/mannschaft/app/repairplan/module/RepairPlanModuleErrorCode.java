package com.mannschaft.app.repairplan.module;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F08.8 修繕長期計画ダッシュボード — モジュール／テンプレ判定専用エラーコード。
 *
 * <p>足軽5 担当範囲（apartment テンプレ判定 / モジュール有効化チェック）の
 * 専用エラーコードを切り出している。{@code REPAIR_PLAN_001}〜{@code REPAIR_PLAN_012}
 * は他足軽（業務 CRUD・カンバン・シミュレータ等）が定義する想定のため、
 * 本 enum は 013〜014 のみを保持する。</p>
 *
 * <p>HTTP ステータスは {@link com.mannschaft.app.common.GlobalExceptionHandler}
 * の {@code ERROR_CODE_STATUS_MAP} で 422 UNPROCESSABLE_ENTITY にマップする。</p>
 *
 * <ul>
 *   <li>REPAIR_PLAN_013: TEMPLATE_NOT_APARTMENT — 対象スコープ（組織またはチーム）が
 *       {@code apartment} テンプレートを採用していない場合。設計書 §2 「対象テンプレート」参照。</li>
 *   <li>REPAIR_PLAN_014: MODULE_NOT_ENABLED — {@code repair_longterm_plan} モジュールが
 *       当該チームで有効化されていない場合。</li>
 * </ul>
 */
@Getter
@RequiredArgsConstructor
public enum RepairPlanModuleErrorCode implements ErrorCode {

    /** 対象スコープが apartment テンプレートではない */
    REPAIR_PLAN_013("REPAIR_PLAN_013",
            "本機能は apartment テンプレート（マンション管理組合）専用です",
            Severity.WARN),

    /** repair_longterm_plan モジュールが当該スコープで有効化されていない */
    REPAIR_PLAN_014("REPAIR_PLAN_014",
            "修繕長期計画モジュールが有効化されていません",
            Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
