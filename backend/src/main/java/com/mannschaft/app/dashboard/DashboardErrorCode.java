package com.mannschaft.app.dashboard;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F02.2 ダッシュボード機能のエラーコード定義。
 * ウィジェット設定・チャットフォルダ・アクティビティフィード関連のエラーを網羅する。
 */
@Getter
@RequiredArgsConstructor
public enum DashboardErrorCode implements ErrorCode {

    /** 無効なウィジェットキー */
    DASHBOARD_001("DASHBOARD_001", "無効なウィジェットキーです", Severity.WARN),

    /** ウィジェットキーがスコープに対して無効 */
    DASHBOARD_002("DASHBOARD_002", "このウィジェットは指定されたスコープでは使用できません", Severity.WARN),

    /** スコープメンバーでない */
    DASHBOARD_003("DASHBOARD_003", "このスコープにアクセスする権限がありません", Severity.WARN),

    /** チームが存在しない */
    DASHBOARD_004("DASHBOARD_004", "指定されたチームが存在しません", Severity.WARN),

    /** 組織が存在しない */
    DASHBOARD_005("DASHBOARD_005", "指定された組織が存在しません", Severity.WARN),

    /** フォルダが存在しない */
    DASHBOARD_006("DASHBOARD_006", "指定されたフォルダが存在しません", Severity.WARN),

    /** フォルダ所有者でない */
    DASHBOARD_007("DASHBOARD_007", "このフォルダにアクセスする権限がありません", Severity.WARN),

    /** 同名フォルダが既に存在 */
    DASHBOARD_008("DASHBOARD_008", "同じ名前のフォルダが既に存在します", Severity.WARN),

    /** フォルダ数上限に到達 */
    DASHBOARD_009("DASHBOARD_009", "フォルダの作成上限（20件）に達しています", Severity.WARN),

    /** 無効なアイテム種別 */
    DASHBOARD_010("DASHBOARD_010", "無効なアイテム種別です", Severity.WARN),

    /** 一括割り当ての件数上限超過 */
    DASHBOARD_011("DASHBOARD_011", "一度に割り当てできるのは20件までです", Severity.WARN),

    /** 一括割り当ての件数が0件 */
    DASHBOARD_012("DASHBOARD_012", "割り当てるアイテムを1件以上指定してください", Severity.WARN),

    /** パフォーマンスモジュールが無効 */
    DASHBOARD_013("DASHBOARD_013", "パフォーマンス管理モジュールが無効です", Severity.WARN),

    /** 無効なスコープタイプ */
    DASHBOARD_014("DASHBOARD_014", "無効なスコープタイプです", Severity.WARN),

    /** sortOrderが負数 */
    DASHBOARD_015("DASHBOARD_015", "並び順は0以上の整数を指定してください", Severity.WARN),

    /** フォルダアイテムが存在しない */
    DASHBOARD_016("DASHBOARD_016", "指定されたフォルダアイテムが存在しません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
