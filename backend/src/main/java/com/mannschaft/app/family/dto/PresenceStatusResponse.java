package com.mannschaft.app.family.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * プレゼンスステータスレスポンスDTO。チームメンバーの最新プレゼンス状態を表す。
 */
@Getter
@RequiredArgsConstructor
public class PresenceStatusResponse {

    private final PresenceEventResponse.UserSummary user;
    private final String status;
    private final String destination;
    private final LocalDateTime expectedReturnAt;
    private final LocalDateTime lastEventAt;
}
