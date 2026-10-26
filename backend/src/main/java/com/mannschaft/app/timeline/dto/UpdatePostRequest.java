package com.mannschaft.app.timeline.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * タイムライン投稿更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdatePostRequest {

    @Size(max = 5000)
    private final String content;
}
