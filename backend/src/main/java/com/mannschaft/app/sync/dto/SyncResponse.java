package com.mannschaft.app.sync.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * オフラインキュー一括同期レスポンス。
 * 個別の処理結果リストとサマリーを含む。
 */
@Getter
@AllArgsConstructor
public class SyncResponse {

    private final List<SyncResultItem> results;
    private final SyncSummary summary;
}
