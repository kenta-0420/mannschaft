package com.mannschaft.app.social.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;

/**
 * フレンドフォルダ作成リクエスト DTO。F01.5 の
 * {@code POST /api/v1/teams/{id}/friend-folders} で使用する。
 *
 * <p>
 * 1 チームあたり最大 20 フォルダまで（上限到達時は Service 層で 409）。
 * </p>
 */
@Getter
public class CreateFolderRequest {

    /** フォルダ名（1〜50 文字、チーム内一意） */
    @NotBlank
    @Size(min = 1, max = 50)
    private final String name;

    /** フォルダの説明（任意、最大 300 文字） */
    @Size(max = 300)
    private final String description;

    /** 表示色（HEX、例: "#10B981"）。省略時はデフォルト値 "#6B7280" が適用される。 */
    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "colorは#から始まる6桁のHEXで指定してください")
    private final String color;

    /**
     * Jackson によるデシリアライズ用コンストラクタ。
     *
     * @param name        フォルダ名
     * @param description フォルダの説明
     * @param color       表示色
     */
    @JsonCreator
    public CreateFolderRequest(
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("color") String color) {
        this.name = name;
        this.description = description;
        this.color = color;
    }
}
