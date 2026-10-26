package com.mannschaft.app.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * メンバープロフィール作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateMemberProfileRequest {

    @NotNull
    private final Long teamPageId;

    private final Long userId;

    @NotBlank
    @Size(max = 100)
    private final String displayName;

    @Size(max = 20)
    private final String memberNumber;

    @Size(max = 500)
    private final String photoS3Key;

    @Size(max = 500)
    private final String bio;

    @Size(max = 100)
    private final String position;

    private final String customFieldValues;
}
