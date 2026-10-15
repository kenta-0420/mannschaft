package com.mannschaft.app.dashboard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * チャットフォルダ作成リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class CreateChatFolderRequest {

    @NotBlank
    @Size(max = 50)
    private final String name;

    @Size(max = 30)
    private final String icon;

    @Size(max = 7)
    private final String color;
}
