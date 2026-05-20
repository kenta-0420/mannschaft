package com.mannschaft.app.publicview.dto;

import com.mannschaft.app.publicview.enums.NameDisclosureMode;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F19.1 Phase 2: 投稿者識別モード変更履歴のレスポンス DTO。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §7.7</p>
 */
public record NameDisclosureChangeLogResponse(
        UUID id,
        NameDisclosureMode oldMode,
        NameDisclosureMode newMode,
        boolean confirmed,
        Long changedBy,
        LocalDateTime changedAt
) {
}
