package com.mannschaft.app.inbox.dto;

import java.util.UUID;

/**
 * F04.11 統合通知インボックス：ラベル表示 DTO。
 *
 * <p>設計書: 01_data_model.md §3.1 / 02_api_design.md §3.4。</p>
 *
 * @param id        ラベルID（UUIDv7）
 * @param name      ラベル名
 * @param color     表示色 #RRGGBB（任意・null 可）
 * @param icon      PrimeIcons 名（任意・null 可）
 * @param sortOrder 表示順
 */
public record LabelDto(
        UUID id,
        String name,
        String color,
        String icon,
        int sortOrder
) {
}
