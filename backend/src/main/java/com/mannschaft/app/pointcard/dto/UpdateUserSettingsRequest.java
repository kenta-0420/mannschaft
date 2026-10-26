package com.mannschaft.app.pointcard.dto;

import jakarta.validation.constraints.Size;

/**
 * ポイントカードウォレットのユーザー設定更新リクエスト DTO。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §6.1 / §7.1
 *
 * <p>各フィールドは null 許容で、null の場合は既存値を維持する（差分適用）。
 * {@code termsVersion} を送信した場合は {@code termsAcceptedAt} が現在時刻で更新される。
 */
public record UpdateUserSettingsRequest(
        Boolean isEnabled,
        @Size(max = 20, message = "termsVersion は 20 文字以内で指定してください")
        String termsVersion,
        Boolean requireBiometricOnShow
) {
}
