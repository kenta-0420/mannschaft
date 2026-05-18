package com.mannschaft.app.admin.dto;

/**
 * F10.X 第二陣 — バッチ直近実行状況応答 DTO。
 *
 * <p>{@code GET /api/v1/system-admin/batch/{name}/status} の戻り値。
 * 直近実行履歴が無い場合は {@code lastJobLog=null} のまま 200 を返す。</p>
 *
 * @param name        バッチ識別子
 * @param lastJobLog  直近の {@link BatchJobLogResponse}（無ければ null）
 */
public record BatchStatusResponse(
        String name,
        BatchJobLogResponse lastJobLog) {
}
