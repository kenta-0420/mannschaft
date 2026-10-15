package com.mannschaft.app.proxyvote.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * ログインユーザーの投票/委任状況レスポンスDTO。
 */
@Getter
@Builder
public class MyStatusResponse {

    private final Boolean hasVoted;
    private final Boolean hasDelegated;
}
