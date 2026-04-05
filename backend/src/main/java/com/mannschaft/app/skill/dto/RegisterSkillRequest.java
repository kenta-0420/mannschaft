package com.mannschaft.app.skill.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * スキル・資格登録リクエスト。
 */
public record RegisterSkillRequest(

        @NotNull
        Long skillCategoryId,

        @NotBlank
        @Size(max = 100)
        String name,

        @Size(max = 200)
        String issuer,

        @Size(max = 100)
        String credentialNumber,

        LocalDate acquiredOn,

        LocalDate expiresAt,

        String certificateS3Key
) {
}
