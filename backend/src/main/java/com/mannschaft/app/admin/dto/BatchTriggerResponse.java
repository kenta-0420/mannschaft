package com.mannschaft.app.admin.dto;

/**
 * F10.X 第二陣 — バッチキック応答 DTO。
 *
 * <p>{@code POST /api/v1/system-admin/batch/{name}/trigger} の戻り値。</p>
 *
 * <ul>
 *   <li>{@code status="ACCEPTED"} — 非同期起動を受け付け、job-pool に投入済み（HTTP 202）</li>
 *   <li>{@code status="COMPLETED"} — 同期実行が正常終了（HTTP 200）</li>
 *   <li>{@code status="FAILED"} — 同期実行で例外発生（HTTP 500）</li>
 *   <li>{@code status="LOCKED"} — 他インスタンスが ShedLock を保持中（HTTP 409）</li>
 *   <li>{@code status="FEATURE_DISABLED"} — {@code @BackgroundFeaturePolicy} が要求する
 *       フィーチャーフラグが無効（HTTP 409。Gate 基盤工事④-A）</li>
 * </ul>
 *
 * @param name      バッチ識別子
 * @param status    "ACCEPTED" | "COMPLETED" | "FAILED" | "LOCKED" | "FEATURE_DISABLED"
 * @param jobLogId  生成された {@code batch_job_logs.id}（非同期受付時は null の可能性あり）
 * @param message   人間可読の補足メッセージ
 */
public record BatchTriggerResponse(
        String name,
        String status,
        Long jobLogId,
        String message) {

    public static BatchTriggerResponse accepted(String name, String message) {
        return new BatchTriggerResponse(name, "ACCEPTED", null, message);
    }

    public static BatchTriggerResponse completed(String name, Long jobLogId, String message) {
        return new BatchTriggerResponse(name, "COMPLETED", jobLogId, message);
    }

    public static BatchTriggerResponse failed(String name, Long jobLogId, String message) {
        return new BatchTriggerResponse(name, "FAILED", jobLogId, message);
    }
}
