package com.mannschaft.app.schedule.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 年間行事コピー実行レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ExecuteCopyResponse {

    private final Long copyLogId;

    private final Integer totalCopied;

    private final Integer totalSkipped;

    private final List<Long> createdScheduleIds;
}
