package com.mannschaft.app.schedule.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 一括削除レスポンスDTO。削除件数とスキップ件数を返す。
 */
@Getter
@RequiredArgsConstructor
public class BatchDeleteResponse {

    private final int deletedCount;
    private final int skippedCount;
}
