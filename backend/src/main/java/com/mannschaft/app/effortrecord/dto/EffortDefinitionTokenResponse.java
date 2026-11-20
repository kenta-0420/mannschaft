package com.mannschaft.app.effortrecord.dto;

import java.time.Instant;

/**
 * F06.4.1 チーム定義 snapshot 登録の応答（API 契約 §3）。
 * definitionToken は 10 分有効の署名 opaque（teamId / definitionUuid / revision / hash / 本人ID を束縛。組織 ID は束縛しない）。
 */
public record EffortDefinitionTokenResponse(
        EffortDefinitionSnapshot definition,
        String definitionToken,
        Instant expiresAt) {
}
