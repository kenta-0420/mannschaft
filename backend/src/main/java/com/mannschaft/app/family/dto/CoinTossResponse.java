package com.mannschaft.app.family.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * コイントスレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class CoinTossResponse {

    private final Long id;
    private final String mode;
    private final String question;
    private final List<String> options;
    private final int resultIndex;
    private final String result;
    private final boolean sharedToChat;
    private final LocalDateTime createdAt;
}
