package com.mannschaft.app.member.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 公開ステータス変更リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class PublishRequest {

    @NotBlank
    private final String status;
}
