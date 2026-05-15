package com.mannschaft.app.village.dto;

import lombok.Builder;

import java.util.List;

/**
 * F17.1 B8 — お気に入り村ピン留め一覧レスポンス（設計書 §4.8）。
 *
 * @param items     ピン項目（sort_order 昇順）
 * @param count     現在のピン件数
 * @param maxLimit  ピン上限（固定 30 件）
 */
@Builder
public record PinListResponse(
        List<PinResponse> items,
        long count,
        int maxLimit
) {}
