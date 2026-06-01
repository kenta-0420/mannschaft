package com.mannschaft.app.inbox.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F04.11 統合通知インボックス：一括操作の結果レスポンス DTO。
 *
 * <p>設計書: 02_api_design.md §3.5（手本: {@code BulkAssignResultResponse}）。
 * 部分失敗を許容するため、成功件数（{@code processed}）とスキップ件数（{@code skipped}）を返す。</p>
 *
 * @param processed 正常に処理できた件数
 * @param skipped   可視性検証失敗・上限超過・冪等スキップ等でスキップした件数
 */
@Getter
@RequiredArgsConstructor
public class BulkResultResponse {

    private final int processed;
    private final int skipped;
}
