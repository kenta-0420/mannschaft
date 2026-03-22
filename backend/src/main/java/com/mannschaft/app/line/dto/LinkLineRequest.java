package com.mannschaft.app.line.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * LINEアカウントリンクリクエスト。
 */
@Getter
@RequiredArgsConstructor
public class LinkLineRequest {

    @NotBlank
    @Size(max = 50)
    private final String lineUserId;

    @Size(max = 100)
    private final String displayName;

    @Size(max = 500)
    private final String pictureUrl;

    @Size(max = 500)
    private final String statusMessage;
}
