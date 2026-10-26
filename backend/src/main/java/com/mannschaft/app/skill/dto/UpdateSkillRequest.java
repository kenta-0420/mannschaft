package com.mannschaft.app.skill.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * スキル・資格更新リクエスト。
 */
public record UpdateSkillRequest(

        @NotBlank
        @Size(max = 100)
        String name,

        String issuer,

        String credentialNumber,

        LocalDate acquiredOn,

        LocalDate expiresAt,

        String certificateS3Key,

        Long version
) {
}
