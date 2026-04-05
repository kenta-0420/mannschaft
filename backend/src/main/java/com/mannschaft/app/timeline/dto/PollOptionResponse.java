package com.mannschaft.app.timeline.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * タイムライン投票選択肢レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class PollOptionResponse {

    private final Long id;
    private final String optionText;
    private final Integer voteCount;
    private final Short sortOrder;
}
