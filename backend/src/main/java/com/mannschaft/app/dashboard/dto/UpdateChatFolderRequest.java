package com.mannschaft.app.dashboard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * チャットフォルダ更新リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class UpdateChatFolderRequest {

    @NotBlank
    @Size(max = 50)
    private final String name;

    @Size(max = 30)
    private final String icon;

    @Size(max = 7)
    private final String color;

    private final Integer sortOrder;
}
