package com.mannschaft.app.jobmatching.service.command;

/**
 * 業務完了報告コマンド（Service 層入力 DTO）。
 *
 * <p>{@code comment} は Worker が Requester に伝える完了時コメント（任意）。
 * MVP では本文は単純な TEXT 保存のみで、添付ファイルは後続 Phase で対応する。</p>
 */
public record ReportCompletionCommand(String comment) {
}
