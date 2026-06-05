package com.mannschaft.app.auth.dto;

import jakarta.validation.constraints.NotNull;

/**
 * F08.9 P3c 後見切替開始リクエスト（{@code POST /api/v1/me/guardianship/switch}・02_api_design §2.2）。
 *
 * <p>切替対象の子のユーザーIDを受け取る。払い手（保護者）は常に
 * {@code SecurityUtils.getCurrentUserId()} で確定するため body には含めない（なりすまし防止）。
 * camelCase 1:1（プロジェクト既定の Jackson 命名）。</p>
 *
 * @param childUserId 切替対象の子のユーザーID（必須）
 */
public record GuardianshipSwitchRequest(
        @NotNull Long childUserId) {
}
