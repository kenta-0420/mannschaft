package com.mannschaft.app.gallery.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 写真情報更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdatePhotoRequest {

    @Size(max = 300)
    private final String caption;

    private final Integer sortOrder;
}
