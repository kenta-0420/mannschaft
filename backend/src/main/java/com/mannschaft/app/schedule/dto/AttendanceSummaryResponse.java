package com.mannschaft.app.schedule.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 出欠サマリーレスポンスDTO。各ステータスの集計を返す。
 */
@Getter
@RequiredArgsConstructor
public class AttendanceSummaryResponse {

    private final int attending;
    private final int partial;
    private final int absent;
    private final int undecided;
    private final int total;
}
