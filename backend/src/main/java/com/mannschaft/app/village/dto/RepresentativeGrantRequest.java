package com.mannschaft.app.village.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 村代表委任 grant リクエスト（F17 Phase 2 U3）。
 *
 * @param membershipId         委任対象の村メンバーシップ ID（TEAM または ORGANIZATION 種別）
 * @param representativeUserId 代表権を委任されるユーザーID（FK は張らないが users.id を想定）
 * @param note                 委任理由メモ（任意・200 文字以内）
 */
public record RepresentativeGrantRequest(
        @NotNull UUID membershipId,
        @NotNull Long representativeUserId,
        @Size(max = 200) String note
) {
}
