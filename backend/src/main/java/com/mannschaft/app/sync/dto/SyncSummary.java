package com.mannschaft.app.sync.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 同期処理のサマリー。合計・成功・コンフリクト・失敗の件数を返す。
 */
@Getter
@AllArgsConstructor
public class SyncSummary {

    private final int total;
    private final int success;
    private final int conflict;
    private final int failed;
}
