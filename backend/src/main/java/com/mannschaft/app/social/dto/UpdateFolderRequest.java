package com.mannschaft.app.social.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;

/**
 * フレンドフォルダ更新リクエスト DTO。F01.5 の
 * {@code PUT /api/v1/teams/{id}/friend-folders/{folderId}} で使用する。
 *
 * <p>
 * 設計書 §5 の方針により部分更新ではなく全量更新。クライアントは現在値も
 * 含めて全フィールドを送信すること。
 * </p>
 */
@Getter
public class UpdateFolderRequest {

    /** フォルダ名（1〜50 文字、チーム内一意） */
    @NotBlank
    @Size(min = 1, max = 50)
    private final String name;

    /** フォルダの説明（任意、最大 300 文字） */
    @Size(max = 300)
    private final String description;

    /** 表示色（HEX、例: "#10B981"） */
    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "colorは#から始まる6桁のHEXで指定してください")
    private final String color;

    /** 並び替え順（0 以上の整数） */
    @PositiveOrZero
    private final Integer sortOrder;

    /**
     * Jackson によるデシリアライズ用コンストラクタ。
     *
     * @param name        フォルダ名
     * @param description フォルダの説明
     * @param color       表示色
     * @param sortOrder   並び替え順
     */
    @JsonCreator
    public UpdateFolderRequest(
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("color") String color,
            @JsonProperty("sortOrder") Integer sortOrder) {
        this.name = name;
        this.description = description;
        this.color = color;
        this.sortOrder = sortOrder;
    }
}
