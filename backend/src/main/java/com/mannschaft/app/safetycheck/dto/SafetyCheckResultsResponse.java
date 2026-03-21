package com.mannschaft.app.safetycheck.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 安否確認結果集計レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class SafetyCheckResultsResponse {

    private final Long safetyCheckId;
    private final Integer totalTargetCount;
    private final Long respondedCount;
    private final Long safeCount;
    private final Long needSupportCount;
    private final Long otherCount;
    private final Long unrespondedCount;
    private final List<SafetyResponseResponse> responses;
}
