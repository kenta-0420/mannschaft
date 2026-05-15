package com.mannschaft.app.village.dto;

import jakarta.validation.constraints.Size;

/**
 * 村メンバー BAN リクエスト。
 *
 * @param reason BAN 理由（最大 500 文字、任意）
 */
public record MembershipBanRequest(@Size(max = 500) String reason) {
}
