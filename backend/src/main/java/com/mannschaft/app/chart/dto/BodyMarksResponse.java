package com.mannschaft.app.chart.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 身体チャートマーク一括更新レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class BodyMarksResponse {

    private final Long chartRecordId;
    private final Integer marksCount;
    private final List<ChartBodyMarkResponse> marks;
}
