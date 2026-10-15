package com.mannschaft.app.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ソーシャルプロフィール作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateProfileRequest {

    @NotBlank
    @Size(min = 3, max = 30)
    @Pattern(regexp = "^[a-zA-Z0-9_]+$")
    private final String handle;

    @Size(max = 50)
    private final String displayName;

    @Size(max = 500)
    private final String avatarUrl;

    @Size(max = 300)
    private final String bio;
}
