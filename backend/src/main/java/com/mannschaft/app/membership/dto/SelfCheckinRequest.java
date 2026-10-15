package com.mannschaft.app.membership.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * セルフチェックインリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class SelfCheckinRequest {

    @NotBlank(message = "拠点QRトークンは必須です")
    private final String locationQrToken;
}
