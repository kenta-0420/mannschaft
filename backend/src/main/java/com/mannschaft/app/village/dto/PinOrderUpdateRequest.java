package com.mannschaft.app.village.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * F17.1 B8 — お気に入り村ピン留め並び替えリクエスト（設計書 §4.8）。
 *
 * <p>クライアントは現在のピン全件を期待順で並べて送信する。
 * Service 側で「現在のピン集合」と一致するか検証し、不一致なら 422 VILLAGE_PIN_ORDER_MISMATCH。</p>
 *
 * @param orderedVillageIds 並び替え後の村 ID 列（先頭が sort_order=0）
 */
public record PinOrderUpdateRequest(
        @NotNull
        @NotEmpty
        List<UUID> orderedVillageIds
) {}
