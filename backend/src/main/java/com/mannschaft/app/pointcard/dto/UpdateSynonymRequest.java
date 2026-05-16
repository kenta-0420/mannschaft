package com.mannschaft.app.pointcard.dto;

import jakarta.validation.constraints.Size;

/**
 * 同義語編集（部分更新）リクエスト DTO。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §7.6
 *
 * <p>{@code synonymDisplay} が非 null の場合は再正規化して
 * {@code synonym_normalized} も更新する。{@code memo} のみの更新も可。
 * {@code providerId} の変更は不可（プロバイダーを変えたい場合は削除して新規作成する）。
 */
public record UpdateSynonymRequest(
        @Size(max = 100) String synonymDisplay,
        @Size(max = 200) String memo
) {
}
