package com.mannschaft.app.membership.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会員証ステータス変更レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class CardStatusResponse {

    private final Long id;
    private final String status;
    private final LocalDateTime suspendedAt;
}
