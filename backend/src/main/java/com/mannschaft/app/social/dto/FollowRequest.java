package com.mannschaft.app.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * フォローリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class FollowRequest {

    @NotBlank
    private final String followedType;

    @NotNull
    private final Long followedId;
}
