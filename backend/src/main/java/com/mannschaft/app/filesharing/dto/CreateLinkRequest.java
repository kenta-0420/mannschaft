package com.mannschaft.app.filesharing.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 共有リンク作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateLinkRequest {

    private final LocalDateTime expiresAt;

    @Size(max = 255)
    private final String password;
}
