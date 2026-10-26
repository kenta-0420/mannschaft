package com.mannschaft.app.membership.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 拠点削除レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class DeleteLocationResponse {

    private final Long id;
    private final LocalDateTime deletedAt;
}
