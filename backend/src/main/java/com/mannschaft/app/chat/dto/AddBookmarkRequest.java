package com.mannschaft.app.chat.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ブックマーク追加リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class AddBookmarkRequest {

    @NotNull
    private final Long messageId;

    @Size(max = 200)
    private final String note;
}
