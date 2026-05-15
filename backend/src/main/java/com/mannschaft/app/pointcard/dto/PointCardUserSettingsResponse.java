package com.mannschaft.app.pointcard.dto;

import com.mannschaft.app.pointcard.entity.PointCardUserSettingsEntity;

import java.time.OffsetDateTime;

/**
 * ポイントカードウォレットのユーザー設定レスポンス DTO。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §6.1
 */
public record PointCardUserSettingsResponse(
        boolean isEnabled,
        OffsetDateTime termsAcceptedAt,
        String termsVersion,
        boolean requireBiometricOnShow
) {

    public static PointCardUserSettingsResponse from(PointCardUserSettingsEntity entity) {
        return new PointCardUserSettingsResponse(
                Boolean.TRUE.equals(entity.getEnabled()),
                entity.getTermsAcceptedAt(),
                entity.getTermsVersion(),
                Boolean.TRUE.equals(entity.getRequireBiometricOnShow())
        );
    }
}
