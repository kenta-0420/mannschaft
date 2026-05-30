package com.mannschaft.app.dashboard;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F22.1: 横スワイプ・ダッシュボード scope-tabs API のエラーコード定義。
 *
 * <p>プレフィックス {@code SCOPE_TAB_}。HTTP ステータスは
 * {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} でマッピングする
 * （SCOPE_TAB_001=403 / 002=400 / 003=400 / 004=404）。</p>
 *
 * <p>設計書: docs/features/F22.1_swipe_scope_dashboard/02_api_design.md §4</p>
 */
@Getter
@RequiredArgsConstructor
public enum DashboardScopeTabErrorCode implements ErrorCode {

    /** orders[].scopeId に非所属の ID が含まれる（1 件でも非所属なら全体拒否）。 */
    SCOPE_TAB_001("SCOPE_TAB_001", "所属していないスコープは並べ替えできません", Severity.WARN),

    /** sortOrder の重複・範囲外（0〜9999）。 */
    SCOPE_TAB_002("SCOPE_TAB_002", "表示順が不正です", Severity.WARN),

    /** scopeType が TEAM / ORGANIZATION 以外。 */
    SCOPE_TAB_003("SCOPE_TAB_003", "スコープ種別が不正です", Severity.WARN),

    /** folderId が存在しない、または自分所有でない（存在隠蔽のため 404）。 */
    SCOPE_TAB_004("SCOPE_TAB_004", "フォルダが見つかりません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
