package com.mannschaft.app.skill.dto;

import com.mannschaft.app.skill.SkillStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * メンバースキル・資格レスポンス。
 */
public record MemberSkillResponse(
        Long id,
        Long skillCategoryId,
        String categoryName,
        Long userId,
        String scopeType,
        Long scopeId,
        String name,
        String issuer,
        String credentialNumber,
        LocalDate acquiredOn,
        LocalDate expiresAt,
        SkillStatus status,
        boolean hasCertificate,
        LocalDateTime verifiedAt,
        Long verifiedBy,
        Long version,
        LocalDateTime createdAt
) {
}
