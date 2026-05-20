package com.mannschaft.app.publicview.dto;

import com.mannschaft.app.publicview.enums.NameDisclosureMode;

import java.time.LocalDateTime;

/**
 * F19.1 Phase 2: Admin 向け supporter_name_disclosure 切替レスポンス DTO。
 *
 * <p>{@code changedAt} は同値更新（変更なし）の場合 {@code null} になる。</p>
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6</p>
 */
public record SupporterNameDisclosureResponse(
        NameDisclosureMode currentMode,
        LocalDateTime changedAt
) {
}
