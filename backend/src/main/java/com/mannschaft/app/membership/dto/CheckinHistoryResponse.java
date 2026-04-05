package com.mannschaft.app.membership.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * チェックイン履歴レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class CheckinHistoryResponse {

    private final Long id;
    private final String checkinType;
    private final LocalDateTime checkedInAt;
    private final String checkedInByName;
    private final String location;
    private final String cardNumber;
    private final String displayName;
}
