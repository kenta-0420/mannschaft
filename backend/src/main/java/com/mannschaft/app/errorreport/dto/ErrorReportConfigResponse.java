package com.mannschaft.app.errorreport.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * F12.5 Phase 2-D — エラーレポート機能の運用設定レスポンス DTO。
 *
 * <p>フロントエンドが GitHub 連携 / AI 分析機能の有効状態を確認するために利用する。
 * トークンや API キーは含まない（有効/無効フラグのみ）。</p>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorReportConfigResponse {

    /** GitHub 連携が有効か（enabled かつ GH_TOKEN/OWNER/REPO 設定済み）。 */
    private boolean githubEnabled;

    /** AI 分析機能が有効か（enabled かつ Claude API キー設定済み）。 */
    private boolean aiEnabled;

    /** AI モデル名（例: claude-haiku-4-5）。 */
    private String aiModel;

    /** AI 月次予算（円）。 */
    private int aiMonthlyBudgetJpy;
}
