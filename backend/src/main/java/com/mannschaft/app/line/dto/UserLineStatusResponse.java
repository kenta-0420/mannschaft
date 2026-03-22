package com.mannschaft.app.line.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * ユーザーLINE連携状態レスポンス。
 */
@Getter
@RequiredArgsConstructor
public class UserLineStatusResponse {

    private final Boolean isLinked;
    private final String lineUserId;
    private final String displayName;
    private final String pictureUrl;
    private final String statusMessage;
    private final Boolean isActive;
    private final LocalDateTime linkedAt;
}
