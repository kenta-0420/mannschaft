package com.mannschaft.app.chart.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 経過グラフ用データレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ProgressResponse {

    private final Long customerUserId;
    private final List<ProgressFieldInfo> fields;
    private final List<ProgressDataPoint> dataPoints;

    @Getter
    @RequiredArgsConstructor
    public static class ProgressFieldInfo {
        private final Long fieldId;
        private final String fieldName;
        private final String unit;
    }

    @Getter
    @RequiredArgsConstructor
    public static class ProgressDataPoint {
        private final LocalDate visitDate;
        private final Map<String, String> values;
    }
}
