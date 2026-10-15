package com.mannschaft.app.circulation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 押印委任リクエスト。
 *
 * <p>F05.2 Phase 11 第三陣 3-B。委任者（受信者本人）から代理人への押印委任。</p>
 *
 * @param delegateeUserId 代理人 user_id（必須）
 * @param reason          委任理由（任意、255 文字以内）
 */
public record StampDelegationRequest(
        @NotNull Long delegateeUserId,
        @Size(max = 255) String reason
) {
}
