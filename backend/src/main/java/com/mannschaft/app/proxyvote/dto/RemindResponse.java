package com.mannschaft.app.proxyvote.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * リマインド送信レスポンスDTO。
 */
@Getter
@Builder
public class RemindResponse {

    private final Integer remindedCount;
    private final Long sessionId;
}
