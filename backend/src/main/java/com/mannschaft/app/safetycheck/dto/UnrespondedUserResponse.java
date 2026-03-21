package com.mannschaft.app.safetycheck.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 未回答ユーザーレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class UnrespondedUserResponse {

    private final Long userId;
    private final String displayName;
}
