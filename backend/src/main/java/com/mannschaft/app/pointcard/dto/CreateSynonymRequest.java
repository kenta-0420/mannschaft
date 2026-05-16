package com.mannschaft.app.pointcard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 同義語新規作成リクエスト DTO。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §7.6
 *
 * <p>SystemAdmin が運営マスタに同義語を追加する際に利用する。
 * {@code synonymDisplay} はサーバー側で {@code ProviderMatchService.normalize()}
 * により正規化されて {@code synonym_normalized} カラムに格納される。
 */
public record CreateSynonymRequest(
        @NotNull UUID providerId,
        @NotBlank @Size(max = 100) String synonymDisplay,
        @Size(max = 200) String memo
) {
}
