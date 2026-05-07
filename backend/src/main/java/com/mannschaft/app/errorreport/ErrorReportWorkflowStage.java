package com.mannschaft.app.errorreport;

/**
 * F12.5 Phase 2 — エラーレポートのワークフロー進捗段階。
 * NULL は「未着手」を意味する（status と組み合わせて利用）。
 *
 * 業務ルール（アプリ層強制）:
 * <ul>
 *   <li>status=NEW → workflow_stage=NULL</li>
 *   <li>status=INVESTIGATING → INVESTIGATION_STARTED〜FIX_IN_PROGRESS</li>
 *   <li>status=RESOLVED → TEST_COMPLETED または RELEASED</li>
 *   <li>status=REOPENED → workflow_stage=NULL にリセット</li>
 *   <li>status=IGNORED → workflow_stage=NULL</li>
 * </ul>
 */
public enum ErrorReportWorkflowStage {
    INVESTIGATION_STARTED,
    ROOT_CAUSE_IDENTIFIED,
    FIX_IN_PROGRESS,
    TEST_COMPLETED,
    RELEASED
}
