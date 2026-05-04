package com.mannschaft.app.corkboard.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * F09.8.1 Phase 3 ピン止めカード横断取得レスポンス（1 件分）。
 *
 * <p>{@code GET /api/v1/users/me/corkboards/pinned-cards} のレスポンスフィールド。
 * 設計書 §4.3 と完全に一致するフィールド構造を持つ。</p>
 *
 * <p>JSON は camelCase（プロジェクト規約）。</p>
 */
@Getter
@RequiredArgsConstructor
public class PinnedCardResponse {

    private final Long cardId;
    private final Long corkboardId;
    private final String corkboardName;
    private final String cardType;
    private final String colorLabel;
    private final String title;
    private final String body;
    private final String userNote;
    private final LocalDateTime pinnedAt;

    /** 参照先メタ。MEMO / SECTION_HEADER 等の純メモカードでは null。 */
    private final PinnedCardReferenceResponse reference;
}
