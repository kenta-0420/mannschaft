package com.mannschaft.app.quickmemo.dto;

import com.mannschaft.app.quickmemo.entity.TagEntity;

/**
 * タグのサマリ（メモ・TODO詳細レスポンスに埋め込む）。
 */
public record TagSummary(Long id, String name, String color) {
    public static TagSummary from(TagEntity entity) {
        return new TagSummary(entity.getId(), entity.getName(), entity.getColor());
    }
}
